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

package org.voltdb;

import java.util.ArrayList;
import java.util.Iterator;

public class DRRoleStats extends StatsSource {
    // This will be replaced by the deployment file role once ENG-11457 and ENG-11459
    private enum Role {
        NONE, MASTER, REPLICA, XDCR
    }

    public enum State {
        DISABLED, // Feature is completely disabled
        ACTIVE,   // Actively exchanging data with remote cluster
        PENDING,  // Waiting to establish connection with remote cluster
        STOPPED;  // Replication broken due to error

        /**
         * Almost like logically ANDing two states together. The precedence is
         * STOPPED->PENDING->ACTIVE->DISABLED. This happened to be the reverse
         * ordinal order.
         *
         * @param other The other state
         * @return The combined state
         */
        public State and(State other)
        {
            if (other.ordinal() > this.ordinal()) {
                return other;
            } else {
                return this;
            }
        }
    }

    private final VoltDBInterface m_vdb;

    public DRRoleStats(VoltDBInterface vdb)
    {
        super(false);
        m_vdb = vdb;
    }

    @Override
    protected void populateColumnSchema(ArrayList<VoltTable.ColumnInfo> columns)
    {
        super.populateColumnSchema(columns);
        columns.add(new VoltTable.ColumnInfo("ROLE", VoltType.STRING));
        columns.add(new VoltTable.ColumnInfo("STATE", VoltType.STRING));
        columns.add(new VoltTable.ColumnInfo("REMOTE_CLUSTER_ID", VoltType.TINYINT));
    }

    @Override
    protected void updateStatsRow(Object rowKey, Object[] rowValues)
    {
        final ProducerDRGateway producer = m_vdb.getNodeDRGateway();
        final DRProducerNodeStats producerStats;
        if (producer != null) {
            producerStats = producer.getNodeDRStats();
        } else {
            producerStats = null;
        }

        final Role role = getRole(producerStats);
        State state = State.DISABLED;

        if (role == Role.XDCR || role == Role.MASTER) {
            if (producerStats != null) {
                state = state.and(producerStats.state);
            }
        }

        if (role == Role.XDCR || role == Role.REPLICA) {
            final ConsumerDRGateway consumer = m_vdb.getConsumerDRGateway();
            if (consumer != null) {
                state = state.and(consumer.getState());
            }
        }

        rowValues[columnNameToIndex.get("ROLE")] = role.name();
        rowValues[columnNameToIndex.get("STATE")] = state.name();
        rowValues[columnNameToIndex.get("REMOTE_CLUSTER_ID")] = -1; // reserved for multi-cluster XDCR

        super.updateStatsRow(rowKey, rowValues);
    }

    @Override
    protected Iterator<Object> getStatsRowKeyIterator(boolean interval)
    {
        return new Iterator<Object>() {
            boolean returnRow = true;

            @Override
            public boolean hasNext()
            {
                return returnRow;
            }

            @Override
            public Object next()
            {
                if (returnRow) {
                    returnRow = false;
                    return new Object();
                } else {
                    return null;
                }
            }
        };
    }

    private Role getRole(DRProducerNodeStats producerStats)
    {
        if (m_vdb.getReplicationRole() == ReplicationRole.REPLICA) {
            return Role.REPLICA;
        } else if (producerStats != null && producerStats.state != State.DISABLED) {
            if (m_vdb.getConsumerDRGateway() == null) {
                return Role.MASTER;
            } else {
                return Role.XDCR;
            }
        } else {
            return Role.NONE;
        }
    }
}
