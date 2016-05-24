/* This file is part of VoltDB.
 * Copyright (C) 2008-2016 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.jni;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.DBBPool;
import org.voltcore.utils.DBBPool.BBContainer;
import org.voltcore.utils.Pair;
import org.voltdb.ParameterSet;
import org.voltdb.PrivateVoltTableFactory;
import org.voltdb.StatsSelector;
import org.voltdb.TableStreamType;
import org.voltdb.TheHashinator.HashinatorConfig;
import org.voltdb.VoltDB;
import org.voltdb.VoltTable;
import org.voltdb.exceptions.EEException;
import org.voltdb.exceptions.SerializableException;
import org.voltdb.messaging.FastDeserializer;
import org.voltdb.sysprocs.saverestore.SnapshotUtil;

import com.google_voltpatches.common.base.Throwables;

/**
 * Wrapper for native Execution Engine library.
 * All native methods are private to make it simple
 * and keep Java/C++ separated as much as possible.
 * These native methods basically just receive IDs in primitive types
 * to simplify JNI calls. This is why these native methods are private and
 * we have public methods that receive objects for type-safety.
 * For each methods, see comments in hstorejni.cpp.
 * Read <a href="package-summary.html">com.horinzontica.jni</a> for
 * guidelines to add/modify JNI methods.
 */
public class ExecutionEngineJNI extends ExecutionEngine {

    /*
     * Threshold of fullness where the EE will start compacting a table's blocks together
     * to free memory and return to the OS. Block will always be freed if they are emptied
     * and since rows are fixed size for a table they are always available for reuse.
     *
     * Valid values are 0-99, where 0 disables compaction completely and 99 compacts the table
     * if it is even 1% empty.
     */
    public static final int EE_COMPACTION_THRESHOLD = Integer.getInteger("EE_COMPACTION_THRESHOLD", 95);

    static {
        if (EE_COMPACTION_THRESHOLD < 0 || EE_COMPACTION_THRESHOLD > 99) {
            VoltDB.crashLocalVoltDB("EE_COMPACTION_THRESHOLD " + EE_COMPACTION_THRESHOLD +
                    " is not valid, must be between 0 and 99", false, null);
        }
    }


    /** The HStoreEngine pointer. */
    private long pointer;

    /** Create a ByteBuffer (in a container) for serializing arguments to C++. Use a direct
    ByteBuffer as it will be passed directly to the C++ code. */
    private BBContainer psetBufferGuard = null;
    private ByteBuffer psetBuffer = null;

    /**
     * A deserializer backed by a direct byte buffer, for fast access from C++.
     * Since this is generally the largest shared buffer between Java and C++
     * it is also generally speaking (since there are no larger shared buffers)
     * the largest possible message size that can be sent between the IPC backend
     * and Java. Every time the size of this buffer is changed the MAX_MSG_SZ define
     * in voltdbipc.cpp must be changed to match so that tests and apps
     * that rely on being able to serialize large results sets will get the same amount of storage
     * when using the IPC backend.
     **/
    private final int MAX_MSG_SZ = (1024*1024*10);
    private final BBContainer deserializerBufferGuard = DBBPool.allocateDirect(MAX_MSG_SZ);
    private FastDeserializer deserializer =
        new FastDeserializer(deserializerBufferGuard.b());

    /*
     * For large result sets the EE will allocate new memory for the results
     * and invoke a callback to set the allocated memory here.
     */
    private ByteBuffer fallbackBuffer = null;

    // voltdbipc uses MAX_MSG_SZ for this buffer, too. Do we care?
    private final BBContainer exceptionBufferGuard = DBBPool.allocateDirect(1024 * 1024 * 5);
    private ByteBuffer exceptionBuffer = exceptionBufferGuard.b();

    /**
     * initialize the native Engine object.
     */
    public ExecutionEngineJNI(
            int clusterIndex,
            long siteId,
            int partitionId,
            int hostId,
            String hostname,
            int drClusterId,
            int defaultDrBufferSize,
            int tempTableMemory,
            HashinatorConfig hashinatorConfig,
            boolean createDrReplicatedStream)
    {
        // base class loads the volt shared library.
        super(siteId, partitionId);

        //exceptionBuffer.order(ByteOrder.nativeOrder());
        LOG.trace("Creating Execution Engine on clusterIndex=" + clusterIndex
                + ", site_id = " + siteId + "...");
        /*
         * (Ning): The reason I'm testing if we're running in Sun's JVM is that
         * EE needs this info in order to decide whether it's safe to install
         * the signal handler or not.
         */
        pointer = nativeCreate(System.getProperty("java.vm.vendor")
                               .toLowerCase().contains("sun microsystems"));
        assert(pointer != 0);
        if (pointer == 0) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeInitialize", true, null);
        }
        nativeSetLogLevels(pointer, EELoggers.getLogLevels());
        int errorCode =
            nativeInitialize(
                    pointer,
                    clusterIndex,
                    siteId,
                    partitionId,
                    hostId,
                    getStringBytes(hostname),
                    drClusterId,
                    defaultDrBufferSize,
                    tempTableMemory * 1024 * 1024,
                    createDrReplicatedStream,
                    EE_COMPACTION_THRESHOLD);
        if (errorCode != ERRORCODE_SUCCESS) {
            throwSerializedException(errorCode);
        }

        setupPsetBuffer(256 * 1024); // 256k seems like a reasonable per-ee number (but is totally pulled from my a**)

        updateHashinator(hashinatorConfig);
        //LOG.info("Initialized Execution Engine");
    }

    private void setupPsetBuffer(int size)
    {
        if (psetBuffer != null) {
            psetBufferGuard.discard();
            psetBuffer = null;
        }

        psetBufferGuard = DBBPool.allocateDirect(size);
        psetBuffer = psetBufferGuard.b();

        assert(pointer != 0);
        if (pointer == 0) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeSetBuffers", true, null);
        }
        int errorCode = nativeSetBuffers(pointer, psetBuffer,
                psetBuffer.capacity(),
                deserializer.buffer(), deserializer.buffer().capacity(),
                exceptionBuffer, exceptionBuffer.capacity());
        if (errorCode != ERRORCODE_SUCCESS) {
            throwSerializedException(errorCode);
        }
    }

    private void clearPsetAndEnsureCapacity(int size)
    {
        assert(psetBuffer != null);
        if (size > psetBuffer.capacity()) {
            //TODO: It MAY be worth logging this source of memory growth,
            // even if it does eventually level off to a worst-case value.
            // The memory is never reclaimed, even on a quiescent system.
            setupPsetBuffer(size);
        }
        else {
            psetBuffer.clear();
        }
    }

    /**
     * Utility method to throw a Runtime exception based on the error code and
     * serialized exception details. The exact value of the error code only
     * matters for an EEException produced when no exception details were
     * serialized.
     **/
    private void throwSerializedException(int errorCode)
            throws RuntimeException
    {
        exceptionBuffer.clear(); // resets position, not content.
        int exceptionLength = exceptionBuffer.getInt();
        if (exceptionLength == 0) {
            throw new EEException(errorCode);
        }
        exceptionBuffer.position(0);
        exceptionBuffer.limit(4 + exceptionLength);
        throw SerializableException.deserializeFromBuffer(exceptionBuffer);
    }

    /**
     * Releases the Engine object.
     * This method is automatically called from #finalize(), but
     * it's recommended to call this method just after you finish
     * using the object.
     */
    @Override
    public boolean release() throws EEException
    {
        LOG.trace("Releasing Execution Engine... " + pointer);
        if (pointer != 0L) {
            int errorCode = nativeDestroy(pointer);
            pointer = 0L;
            if (errorCode != ERRORCODE_SUCCESS) {
                throwSerializedException(errorCode);
            }
        }
        deserializer = null;
        deserializerBufferGuard.discard();
        exceptionBuffer = null;
        exceptionBufferGuard.discard();
        if (psetBuffer != null) {
            psetBuffer = null;
            psetBufferGuard.discard();
        }
        LOG.trace("Released Execution Engine.");
        return true;
    }

    /**
     *  Provide a serialized catalog and initialize version 0 of the engine's
     *  catalog.
     */
    @Override
    protected boolean loadCatalog(long timestamp, byte[] catalogBytes) throws EEException
    {
        LOG.trace("Loading Application Catalog...");
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeLoadCatalog", true, null);
        }
        int errorCode = nativeLoadCatalog(pointer, timestamp, catalogBytes);
        if (errorCode != ERRORCODE_SUCCESS) {
            throwSerializedException(errorCode);
        }
        //LOG.info("Loaded Catalog.");
        return true;
    }

    /**
     * Provide a catalog diff and a new catalog version and update the
     * engine's catalog.
     */
    @Override
    public boolean updateCatalog(long timestamp, String catalogDiffs) throws EEException
    {
        LOG.trace("Loading Application Catalog...");
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeUpdateCatalog", true, null);
        }
        int errorCode = nativeUpdateCatalog(pointer, timestamp, getStringBytes(catalogDiffs));
        if (errorCode != ERRORCODE_SUCCESS) {
            throwSerializedException(errorCode);
        }
        return true;
    }

    /**
     * @param undoToken Token identifying undo quantum for generated undo info
     */
    @Override
    protected VoltTable[] coreExecutePlanFragments(
            int numFragmentIds,
            long[] planFragmentIds,
            long[] inputDepIds,
            Object[] parameterSets,
            long txnId,
            long spHandle,
            long lastCommittedSpHandle,
            long uniqueId,
            long undoToken) throws EEException
    {
        // serialize the param sets
        int allPsetSize = 0;
        for (int i = 0; i < numFragmentIds; ++i) {
            if (parameterSets[i] instanceof ByteBuffer) {
                allPsetSize += ((ByteBuffer) parameterSets[i]).limit();
            }
            else {
                allPsetSize += ((ParameterSet) parameterSets[i]).getSerializedSize();
            }
        }

        clearPsetAndEnsureCapacity(allPsetSize);
        for (int i = 0; i < numFragmentIds; ++i) {
            if (parameterSets[i] instanceof ByteBuffer) {
                ByteBuffer buf = (ByteBuffer) parameterSets[i];
                psetBuffer.put(buf);
            }
            else {
                ParameterSet pset = (ParameterSet) parameterSets[i];
                try {
                    pset.flattenToBuffer(psetBuffer);
                }
                catch (IOException exception) {
                    throw new RuntimeException("Error serializing parameters for SQL batch element: " +
                                               i + " with plan fragment ID: " + planFragmentIds[i] +
                                               " and with params: " +
                                               pset.toJSONString(), exception);
                }
            }
        }
        // checkMaxFsSize();

        // Execute the plan, passing a raw pointer to the byte buffers for input and output
        //Clear is destructive, do it before the native call
        deserializer.clear();
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeExecutePlanFragments", true, null);
        }
        int errorCode =
            nativeExecutePlanFragments(
                    pointer,
                    numFragmentIds,
                    planFragmentIds,
                    inputDepIds,
                    txnId,
                    spHandle,
                    lastCommittedSpHandle,
                    uniqueId,
                    undoToken);

        try {
            if (errorCode != ERRORCODE_SUCCESS) {
                throwSerializedException(errorCode);
            }
            FastDeserializer fds = (fallbackBuffer == null) ?
                    deserializer :
                    new FastDeserializer(fallbackBuffer);
            // read the complete size of the buffer used
            int totalSize = fds.readInt();
            // check if anything was changed
            if (fds.readBoolean()) {
                m_dirty = true;
            }
            // get a copy of the buffer
            ByteBuffer fullBacking = fds.readBuffer(totalSize);
            VoltTable[] results = new VoltTable[numFragmentIds];
            for (int i = 0; i < numFragmentIds; ++i) {
                int numdeps = fullBacking.getInt(); // number of dependencies for this frag
                assert(numdeps == 1);
                @SuppressWarnings("unused")
                int depid = fullBacking.getInt(); // ignore the dependency id
                int tableSize = fullBacking.getInt();
                // reasonableness check
                assert(tableSize < 50000000);
                ByteBuffer tableBacking = fullBacking.slice();
                fullBacking.position(fullBacking.position() + tableSize);
                tableBacking.limit(tableSize);

                results[i] = PrivateVoltTableFactory.createVoltTableFromBuffer(tableBacking, true);
            }
            return results;
        }
        finally {
            fallbackBuffer = null;
        }
    }

    @Override
    public VoltTable serializeTable(int tableId) throws EEException
    {
        if (HOST_TRACE_ENABLED) {
            LOG.trace("Retrieving VoltTable:" + tableId);
        }
        //Clear is destructive, do it before the native call
        deserializer.clear();
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeSerializeTable", true, null);
        }
        int errorCode = nativeSerializeTable(pointer, tableId, deserializer.buffer(),
                deserializer.buffer().capacity());
        if (errorCode != ERRORCODE_SUCCESS) {
            throwSerializedException(errorCode);
        }
        return PrivateVoltTableFactory.createVoltTableFromSharedBuffer(deserializer.buffer());
    }

    @Override
    public byte[] loadTable(int tableId,
        VoltTable table,
        long txnId,
        long spHandle,
        long lastCommittedSpHandle,
        long uniqueId,
        boolean returnUniqueViolations,
        boolean shouldDRStream,
        long undoToken) throws EEException
    {
        if (HOST_TRACE_ENABLED) {
            LOG.trace("loading table id=" + tableId + "...");
        }
        byte[] serialized_table = PrivateVoltTableFactory.getTableDataReference(table).array();
        if (HOST_TRACE_ENABLED) {
            LOG.trace("passing " + serialized_table.length + " bytes to EE...");
        }

        //Clear is destructive, do it before the native call
        deserializer.clear();
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeLoadTable", true, null);
        }
        int errorCode = nativeLoadTable(pointer, tableId, serialized_table, txnId,
                spHandle, lastCommittedSpHandle, uniqueId,
                returnUniqueViolations, shouldDRStream, undoToken);
        if (errorCode != ERRORCODE_SUCCESS) {
            throwSerializedException(errorCode);
        }

        int length = deserializer.readInt();
        if (length == 0) {
           return null;
        }
        if (length < 0) {
            VoltDB.crashLocalVoltDB("Length shouldn't be < 0", true, null);
        }

        byte uniqueViolations[] = new byte[length];
        deserializer.readFully(uniqueViolations);

        return uniqueViolations;
    }

    /**
     * This method should be called roughly every second. It allows the EE
     * to do periodic non-transactional work.
     * @param time The current time in milliseconds since the epoch. See
     * System.currentTimeMillis();
     */
    @Override
    public boolean tick(long time, long lastCommittedTxnId)
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeTick", true, null);
        }
        nativeTick(pointer, time, lastCommittedTxnId);
        return true;
    }

    @Override
    public boolean quiesce(long lastCommittedTxnId)
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeQuiesce", true, null);
        }
        nativeQuiesce(pointer, lastCommittedTxnId);
        return true;
    }

    /**
     * Retrieve a set of statistics using the specified selector from the StatisticsSelector enum.
     * @param selector Selector from StatisticsSelector specifying what statistics to retrieve
     * @param locators CatalogIds specifying what set of items the stats should come from.
     * @param interval Return counters since the beginning or since this method was last invoked
     * @param now Timestamp to return with each row
     * @return Array of results tables. An array of length 0 indicates there are no results. On error, an EEException will be thrown.
     */
    @Override
    public VoltTable[] getStats(
            StatsSelector selector,
            int locators[],
            boolean interval,
            Long now)
    {
        //Clear is destructive, do it before the native call
        deserializer.clear();
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeGetStats", true, null);
        }
        int numResults = nativeGetStats(pointer, selector.ordinal(), locators, interval, now);
        if (numResults == -1) {
            throwSerializedException(ERRORCODE_ERROR);
        }

        deserializer.readInt();//Ignore the length of the result tables

        ByteBuffer buf = (fallbackBuffer == null) ? deserializer.buffer() : fallbackBuffer;
        VoltTable results[] = new VoltTable[numResults];
        for (int ii = 0; ii < numResults; ii++) {
            int len = buf.getInt();
            byte[] bufCopy = new byte[len];
            buf.get(bufCopy);
            results[ii] = PrivateVoltTableFactory.createVoltTableFromBuffer(ByteBuffer.wrap(bufCopy), true);
        }
        return results;
    }

    @Override
    public boolean toggleProfiler(int toggle)
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeToggleProfiler", true, null);
        }
        nativeToggleProfiler(pointer, toggle);
        return true;
    }

    @Override
    public boolean releaseUndoToken(long undoToken)
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeReleaseUndoToken", true, null);
        }
        nativeReleaseUndoToken(pointer, undoToken);
        return true;
    }

    @Override
    public boolean undoUndoToken(long undoToken)
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeUndoUndoToken", true, null);
        }
        nativeUndoUndoToken(pointer, undoToken);
        return true;
    }

    /**
     * Set the log levels to be used when logging in this engine
     * @param logLevels Levels to set
     * @throws EEException
     * @returns true on success false on failure
     */
    @Override
    public boolean setLogLevels(long logLevels) throws EEException
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeSetLogLevels", true, null);
        }
        nativeSetLogLevels(pointer, logLevels);
        return true;
    }

    @Override
    public boolean activateTableStream(int tableId,
                                       TableStreamType streamType,
                                       long undoQuantumToken,
                                       byte[] predicates)
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeActivateTableStream", true, null);
        }
        return nativeActivateTableStream(pointer, tableId, streamType.ordinal(),
                                         undoQuantumToken, predicates);
    }

    @Override
    public Pair<Long, int[]> tableStreamSerializeMore(int tableId,
                                                      TableStreamType streamType,
                                                      List<BBContainer> outputBuffers)
    {
        //Clear is destructive, do it before the native call
        deserializer.clear();
        byte[] bytes = (outputBuffers == null) ?
                null : SnapshotUtil.OutputBuffersToBytes(outputBuffers);
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeTableStreamSerializeMore", true, null);
        }
        long remaining = nativeTableStreamSerializeMore(pointer,
                                                        tableId,
                                                        streamType.ordinal(),
                                                        bytes);
        int[] positions = null;
        assert(deserializer != null);
        int count;
        count = deserializer.readInt();
        if (count > 0) {
            positions = new int[count];
            for (int i = 0; i < count; i++) {
                positions[i] = deserializer.readInt();
            }
            return Pair.of(remaining, positions);
        }

        return Pair.of(remaining, new int[] {0});
    }

    /**
     * Instruct the EE to execute an Export poll and/or ack action. Poll response
     * data is returned in the usual results buffer, length preceded as usual.
     */
    @Override
    public boolean exportAction(boolean syncAction,
            long ackTxnId, long seqNo, int partitionId, String tableSignature)
    {
        //Clear is destructive, do it before the native call
        deserializer.clear();
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeExportAction", true, null);
        }
        long retval = nativeExportAction(pointer,
                                         syncAction, ackTxnId, seqNo, getStringBytes(tableSignature));
        if (retval < 0) {
            LOG.info("exportAction failed.  syncAction: " + syncAction + ", ackTxnId: " +
                    ackTxnId + ", seqNo: " + seqNo + ", partitionId: " + partitionId +
                    ", tableSignature: " + tableSignature);
        }
        return true;
    }

    @Override
    public long[] getUSOForExportTable(String tableSignature)
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeGetUSOForExportTable", true, null);
        }
        return nativeGetUSOForExportTable(pointer, getStringBytes(tableSignature));
    }

    @Override
    public boolean processRecoveryMessage(ByteBuffer buffer, long bufferPointer)
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeProcessRecoveryMessage", true, null);
        }
        nativeProcessRecoveryMessage(pointer, bufferPointer, buffer.position(), buffer.remaining());
        return true;
    }

    @Override
    public long tableHashCode(int tableId)
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeTableHashCode", true, null);
        }
        return nativeTableHashCode(pointer, tableId);
    }

    @Override
    public int hashinate(
            Object value,
            HashinatorConfig config)
    {
        ParameterSet parameterSet = ParameterSet.fromArrayNoCopy(value, config.type.typeId(), config.configBytes);

        // serialize the param set
        clearPsetAndEnsureCapacity(parameterSet.getSerializedSize());
        try {
            parameterSet.flattenToBuffer(psetBuffer);
        }
        catch (IOException exception) {
            throw new RuntimeException(exception); // can't happen
        }

        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeHashinate", true, null);
        }
        return nativeHashinate(pointer, config.configPtr, config.numTokens);
    }

    @Override
    public boolean updateHashinator(HashinatorConfig config)
    {
        if (config.configPtr == 0) {
            ParameterSet parameterSet = ParameterSet.fromArrayNoCopy(config.configBytes);

            // serialize the param set
            clearPsetAndEnsureCapacity(parameterSet.getSerializedSize());
            try {
                parameterSet.flattenToBuffer(psetBuffer);
            }
            catch (IOException exception) {
                throw new RuntimeException(exception); // can't happen
            }
        }

        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeUpdateHashinator", true, null);
        }
        nativeUpdateHashinator(pointer, config.type.typeId(), config.configPtr, config.numTokens);
        return true;
    }

    @Override
    public long applyBinaryLog(ByteBuffer log, long txnId, long spHandle, long lastCommittedSpHandle, long uniqueId,
                               int remoteClusterId, long undoToken) throws EEException
    {
        assert(pointer != 0L);
        if (pointer == 0L) {
            VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeApplyBinaryLog", true, null);
        }
        long rowCount = nativeApplyBinaryLog(pointer, txnId, spHandle, lastCommittedSpHandle, uniqueId, remoteClusterId, undoToken);
        if (rowCount < 0) {
            throwSerializedException((int)rowCount);

        }
        return rowCount;
    }

    @Override
    public long getThreadLocalPoolAllocations()
    {
        return nativeGetThreadLocalPoolAllocations();
    }

    /*
     * Instead of using the reusable output buffer to get results for the next batch,
     * use this buffer allocated by the EE. This is for one time use.
     */
    public void fallbackToEEAllocatedBuffer(ByteBuffer buffer)
    {
        assert(buffer != null);
        assert(fallbackBuffer == null);
        fallbackBuffer = buffer;
    }

    @Override
    public byte[] executeTask(TaskType taskType, ByteBuffer task) throws EEException
    {
        try {
            psetBuffer.putLong(0, taskType.taskId);

            //Clear is destructive, do it before the native call
            deserializer.clear();
            assert(pointer != 0L);
            if (pointer == 0L) {
                VoltDB.crashLocalVoltDB("Engine pointer should be set prior to nativeExecuteTask", true, null);
            }
            int errorCode = nativeExecuteTask(pointer);
            if (errorCode != ERRORCODE_SUCCESS) {
                throwSerializedException(errorCode);
            }
            return deserializer.readByteArray();
        }
        catch (IOException e) {
            Throwables.propagate(e);
        }
        return null;
    }

    @Override
    public ByteBuffer getParamBufferForExecuteTask(int requiredCapacity)
    {
        clearPsetAndEnsureCapacity(8 + requiredCapacity);
        psetBuffer.position(8);
        return psetBuffer;
    }
}
