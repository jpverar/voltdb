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
    public DRRoleStats(ProducerDRGateway producer, ConsumerDRGateway consumer)
    {
        super(false);
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
        rowValues[columnNameToIndex.get("ROLE")] = "NONE";
        rowValues[columnNameToIndex.get("STATE")] = "DISABLED";
        rowValues[columnNameToIndex.get("REMOTE_CLUSTER_ID")] = -1;
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
}
