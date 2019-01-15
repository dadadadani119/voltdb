/* This file is part of VoltDB.
 * Copyright (C) 2008-2019 VoltDB Inc.
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

package org.voltdb.plannerv2.guards;

/**
 * Accepts all SELECT queries.
 *
 * @author Yiqun Zhang
 * @since 9.0
 */
public class AcceptAllSelect extends CalciteCompatibilityCheck {

    @Override protected final boolean doCheck(String sql) {
        return sql.toUpperCase().startsWith("SELECT");
    }

    /**
     * Some of the validation errors happened because of the lack of support we ought to add
     * to Calcite. We need to fallback for those cases.
     * @param message the error message.
     * @return true if we need to fallback.
     */
    public static boolean fallback(String message) {
        if (message.contains("No match found for function signature")) {
            return true;
        }
        return false;
    }
}
