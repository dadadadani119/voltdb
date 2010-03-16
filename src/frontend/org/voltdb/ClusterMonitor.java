/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb;


import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.voltdb.client.*;

/**
 * Polls a Volt cluster via the statistics sysproc and ELTs the results to a database via JDBC.
 * Can be embedded in an application or invoked from the command line
 *
 */
public class ClusterMonitor {

    private static final String tablePrefix = "ma_";

    private static final String initiatorsTable = tablePrefix + "initiators";
    private static final String instancesTable = tablePrefix + "instances";
    private static final String iostatsTable = tablePrefix + "iostats";
    private static final String proceduresTable = tablePrefix + "procedures";
    private static final String tablestatsTable = tablePrefix + "tablestats";

    private static final String retrieveInstanceId = new String(" select instanceId from " + instancesTable +
            " where startTime = ? and leaderAddress = ?;");

    private static final String createInstanceStatement = new String("insert into " + instancesTable +
            " ( startTime, leaderAddress, applicationName, subApplicationName, numHosts, " +
            "numPartitionsPerHost, numTotalPartitions, numKSafety) " +
            "values ( ?, ?, ?, ?, ?, ?, ?, ?);");

    private static final String insertInitiatorStatement = new String("insert into " + initiatorsTable +
            " ( instanceId, tsEvent, hostId, hostName, siteId, connectionId, connectionHostname, " +
            " procedureName, numInvocations, avgExecutionTime, minExecutionTime, maxExecutionTime, " +
            " numAborts, numFailures )" +
            " values ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);");

    private static final String insertIOStatsStatement = new String("insert into " + iostatsTable +
            " ( instanceId, tsEvent, hostId, hostName, connectionId, connectionHostname, " +
            " numBytesRead, numMessagesRead, numBytesWritten, numMessagesWritten )" +
            " values ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);");

    private static final String insertProceduresStatement = new String("insert into " + proceduresTable +
            " (instanceId, tsEvent, hostId, hostName, siteId, procedureName, numInvocations, " +
            " numTimedInvocations, avgExecutionTime, minExecutionTime, maxExecutionTime, numAborts, numFailures )" +
            " values ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);");

    private static final String insertTableStatsStatement = new String("insert into " + tablestatsTable +
            " (instanceId, tsEvent, hostId, hostName, siteId, partitionId, " +
            " tableName, tableType, numActiveTuples, numAllocatedTuples, numDeletedTuples )" +
            " values ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);");

    private final Thread m_loadThread = new Thread(new Loader(), "Client stats loader");

    public static void main(String args[]) throws Exception {
        String application = "";
        String subApplication = null;
        int hosts = -1;
        int partitionsPerHost = -1;
        int totalPartitions = -1;
        int kFactor = -1;
        String databaseURL = "";
        String voltUsername = "";
        String voltPassword = "";
        long pollInterval = 10000;

        ArrayList<String> voltHosts = new ArrayList<String>();
        for (String arg : args) {
            String[] parts = arg.split("=",2);
            if (parts.length == 1) {
                continue;
            } else if (parts[1].startsWith("${")) {
                continue;
            } else if (parts[0].equals("application")) {
                application = parts[1];
            } else if (parts[0].equals("subApplication")) {
                subApplication = parts[1];
            } else if (parts[0].equals("hosts")) {
                hosts = Integer.parseInt(parts[1]);
                if (hosts < 1) {
                    System.err.println("hosts can't be less than 1");
                    System.exit(-1);
                }
            } else if (parts[0].equals("partitionsPerHost")) {
                partitionsPerHost = Integer.parseInt(parts[1]);
                if (partitionsPerHost < 1) {
                    System.err.println("partitionsPerHost can't be less than 1");
                    System.exit(-1);
                }
            } else if (parts[0].equals("kFactor")) {
                kFactor = Integer.parseInt(parts[1]);
                if (kFactor < 0) {
                    System.err.println("kFactor can't be less than 0");
                    System.exit(-1);
                }
            } else if (parts[0].startsWith("voltHost")) {
                final String host = parts[1];
                if (host.isEmpty()) {
                    System.err.println("voltHost can't be empty");
                    System.exit(-1);
                }
                voltHosts.add(host);
            } else if (parts[0].equals("voltUsername")) {
                voltUsername = parts[1];
            } else if (parts[0].equals("voltPassword")) {
                voltPassword = parts[1];
            } else if (parts[0].equals("databaseURL")) {
                databaseURL = parts[1];
                if (databaseURL.isEmpty()) {
                    System.err.println("databaseURL cannot be empty");
                    System.exit(-1);
                }
            } else if (parts[0].equals("pollInterval")) {
                pollInterval = Long.parseLong(parts[1]);
                if (pollInterval < 200) {
                    System.err.println("Poll interval is excessive, try 1 second");
                    System.exit(-1);
                }
                if (pollInterval < 1000) {
                    System.err.println("Warning, an excessively frequent poll interval incurs overhead");
                }
            }
        }

        if (partitionsPerHost != -1 && hosts != -1) {
            totalPartitions = partitionsPerHost * hosts;
        }
        boolean err = false;
        if (voltHosts.isEmpty()) {
            System.err.println("No volt hosts specified");
            System.err.println("Usage: voltHost1=host1 voltHost2=host2 ... ");
            err = true;
        }

        if (application.isEmpty()) {
            System.err.println("No application name specified");
            System.err.println("Usage: application=bingo");
            err = true;
        }

        if (databaseURL.isEmpty()) {
            System.err.println("No database specified. Be sure to incude username and password in URL.");
            System.err.println("Usage: databaseURL=jdbc:mysql://[host][,failoverhost...]" +
                    "[:port]/[database][?propertyName1][=propertyValue1][&propertyName2][=propertyValue2]...");
            err = true;
        }

        if (err) {
            System.exit(-1);
        }

        final ClusterMonitor cm = new ClusterMonitor(
                application,
                subApplication,
                hosts,
                partitionsPerHost,
                totalPartitions,
                kFactor,
                voltHosts,
                voltUsername,
                voltPassword,
                databaseURL,
                pollInterval);

        cm.start();
        cm.m_loadThread.join();
    }

    private final Connection m_conn;
    private final Client m_client;
    private final Integer m_instanceId;
    private final long m_pollInterval;
    private final PreparedStatement insertInitiator;
    private final PreparedStatement insertIOStats;
    private final PreparedStatement insertProcedures;
    private final PreparedStatement insertTableStats;

    public ClusterMonitor(
            String application,
            String subApplication,
            int hosts,
            int partitionsPerHost,
            int totalPartitions,
            int kFactor,
            ArrayList<String> voltHosts,
            String voltUsername,
            String voltPassword,
            String databaseURL,
            long pollInterval) throws SQLException {
        m_pollInterval = pollInterval;
        m_client = ClientFactory.createClient();
        int successfulConnections = 0;
        for (String host : voltHosts) {
            try {
                m_client.createConnection(host, voltUsername, voltPassword);
                successfulConnections++;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (successfulConnections == 0) {
            throw new RuntimeException("Unable to open any connections to the cluster");
        }

        m_conn =  DriverManager.getConnection(databaseURL);
        // safest thing possible
        m_conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        // commit everything or nothing
        m_conn.setAutoCommit(false);

        int instanceId = -1;

        CallableStatement retrieveStmt = m_conn.prepareCall(retrieveInstanceId);
        retrieveStmt.setLong( 1, (Long)m_client.getInstanceId()[0]);
        retrieveStmt.setInt( 2, (Integer)m_client.getInstanceId()[1]);
        retrieveStmt.execute();
        ResultSet instanceIdSet = retrieveStmt.getResultSet();
        while (instanceIdSet.next()) {
            instanceId = instanceIdSet.getInt(1);
        }
        instanceIdSet.close();
        retrieveStmt.close();
        insertInitiator =
            m_conn.prepareStatement( insertInitiatorStatement);
        insertIOStats = m_conn.prepareStatement( insertIOStatsStatement);
        insertProcedures = m_conn.prepareStatement( insertProceduresStatement);
        insertTableStats = m_conn.prepareStatement( insertTableStatsStatement);

        if (instanceId < 0) {
            boolean success = false;
            final PreparedStatement statement =
                m_conn.prepareStatement( createInstanceStatement, Statement.RETURN_GENERATED_KEYS);
            try {
                int index = 1;
                statement.setLong( index++, (Long)m_client.getInstanceId()[0]);
                statement.setInt( index++, (Integer)m_client.getInstanceId()[1]);
                statement.setString( index++, application);
                if (subApplication != null) {
                    statement.setString( index++, subApplication);
                }  else {
                    statement.setNull( index++, Types.VARCHAR);
                }
                if (hosts > 0) {
                    statement.setInt( index++, hosts);
                } else {
                    statement.setNull( index++, Types.INTEGER);
                }
                if (partitionsPerHost > 0) {
                    statement.setInt( index++, partitionsPerHost);
                } else {
                    statement.setNull( index++, Types.INTEGER);
                }
                if (totalPartitions > 0) {
                    statement.setInt( index++, totalPartitions);
                } else {
                    statement.setNull( index++, Types.INTEGER);
                }
                if (kFactor >= 0) {
                    statement.setInt( index++, kFactor);
                } else {
                    statement.setNull( index++, Types.INTEGER);
                }
                statement.execute();
                final ResultSet generatedKeys = statement.getGeneratedKeys();
                if (generatedKeys.first()) {
                    instanceId = generatedKeys.getInt(1);
                    success = true;
                } else {
                    generatedKeys.close();
                    throw new SQLException("Create instance statement didn't return an instance key");
                }
                generatedKeys.close();
            } finally {
                statement.close();
                if (success) {
                    m_conn.commit();
                } else {
                    m_conn.close();
                }
            }
        }
        m_instanceId = instanceId;
    }

    public void start() {
        m_loadThread.start();
    }

    public synchronized void stop() throws InterruptedException {
        m_shouldStop = true;
        notifyAll();
        while (!m_stopped) {
            wait();
        }
    }

    private boolean m_shouldStop = false;
    private volatile boolean m_stopped = false;

    private class Loader implements Runnable {
        @Override
        public void run() {
            synchronized (ClusterMonitor.this) {
                try {
                    while (true) {
                        VoltTable stats[] = m_client.callProcedure("@Statistics", "management", (byte)1);
                        final VoltTable initiatorResults = stats[0];
                        final VoltTable procedureResults = stats[1];
                        final VoltTable ioResults = stats[2];
                        final VoltTable tableResults = stats[3];
                        sendInitiatorResults(initiatorResults);
                        sendProcedureResults(procedureResults);
                        sendIOResults(ioResults);
                        sendTableResults(tableResults);
                        m_conn.commit();
                        if (m_shouldStop) {
                            break;
                        }
                        ClusterMonitor.this.wait(m_pollInterval);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    m_stopped = true;
                    ClusterMonitor.this.notifyAll();
                }
            }
        }

        private void sendInitiatorResults(VoltTable initiatorResults) throws Exception {
            boolean first = true;
            try {
                while (initiatorResults.advanceRow()) {
                    int index = 1;
                    if (first) {
                        first = false;
                        insertInitiator.setInt( index++, m_instanceId);
                        insertInitiator.setTimestamp( index++, new Timestamp(initiatorResults.getLong("TIMESTAMP")));
                    } else {
                        index += 2;
                    }
                    insertInitiator.setLong( index++, initiatorResults.getLong("HOST_ID"));
                    insertInitiator.setString( index++, initiatorResults.getString("HOSTNAME"));
                    insertInitiator.setLong( index++, initiatorResults.getLong("SITE_ID"));
                    insertInitiator.setLong( index++, initiatorResults.getLong("CONNECTION_ID"));
                    insertInitiator.setString( index++, initiatorResults.getString("CONNECTION_HOSTNAME"));
                    insertInitiator.setString( index++, initiatorResults.getString("PROCEDURE_NAME"));
                    insertInitiator.setLong( index++, initiatorResults.getLong("INVOCATIONS"));
                    insertInitiator.setLong( index++, initiatorResults.getLong("AVG_EXECUTION_TIME"));
                    insertInitiator.setLong( index++, initiatorResults.getLong("MIN_EXECUTION_TIME"));
                    insertInitiator.setLong( index++, initiatorResults.getLong("MAX_EXECUTION_TIME"));
                    insertInitiator.setLong( index++, initiatorResults.getLong("ABORTS"));
                    insertInitiator.setLong( index++, initiatorResults.getLong("FAILURES"));
                    insertInitiator.addBatch();
                }
            } finally {
                try {
                    insertInitiator.executeBatch();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
        private void sendTableResults(VoltTable tableResults) throws SQLException {
            boolean first = true;
            try {
                while (tableResults.advanceRow()) {
                    int index = 1;
                    if (first) {
                        first = false;
                        insertTableStats.setInt( index++, m_instanceId);
                        insertTableStats.setTimestamp( index++, new Timestamp(tableResults.getLong("TIMESTAMP")));
                    } else {
                        index += 2;
                    }
                    insertTableStats.setLong( index++, tableResults.getLong("HOST_ID"));
                    insertTableStats.setString( index++, tableResults.getString("HOSTNAME"));
                    insertTableStats.setLong( index++, tableResults.getLong("SITE_ID"));
                    insertTableStats.setLong( index++, tableResults.getLong("PARTITION_ID"));
                    insertTableStats.setString( index++, tableResults.getString("TABLE_NAME"));
                    insertTableStats.setString( index++, tableResults.getString("TABLE_TYPE"));
                    insertTableStats.setLong( index++, tableResults.getLong("TABLE_ACTIVE_TUPLE_COUNT"));
                    insertTableStats.setLong( index++, tableResults.getLong("TABLE_ALLOCATED_TUPLE_COUNT"));
                    insertTableStats.setLong( index++, tableResults.getLong("TABLE_DELETED_TUPLE_COUNT"));
                    insertTableStats.addBatch();
                }
            } finally {
                try {
                    insertTableStats.executeBatch();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        private void sendIOResults(VoltTable ioResults) throws SQLException {
            boolean first = true;
            try {
                while (ioResults.advanceRow()) {
                    int index = 1;
                    if (first) {
                        first = false;
                        insertIOStats.setInt( index++, m_instanceId);
                        insertIOStats.setTimestamp( index++, new Timestamp(ioResults.getLong("TIMESTAMP")));
                    } else {
                        index += 2;
                    }
                    insertIOStats.setLong( index++, ioResults.getLong("HOST_ID"));
                    insertIOStats.setString( index++, ioResults.getString("HOSTNAME"));
                    insertIOStats.setLong( index++, ioResults.getLong("CONNECTION_ID"));
                    insertIOStats.setString( index++, ioResults.getString("CONNECTION_HOSTNAME"));
                    insertIOStats.setLong( index++, ioResults.getLong("BYTES_READ"));
                    insertIOStats.setLong( index++, ioResults.getLong("MESSAGES_READ"));
                    insertIOStats.setLong( index++, ioResults.getLong("BYTES_WRITTEN"));
                    insertIOStats.setLong( index++, ioResults.getLong("MESSAGES_WRITTEN"));
                    insertIOStats.addBatch();
                }
            } finally {
                try {
                    insertIOStats.executeBatch();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        private void sendProcedureResults(VoltTable procedureResults) throws SQLException {
            boolean first = true;
            try {
                while (procedureResults.advanceRow()) {
                    int index = 1;
                    if (first) {
                        first = false;
                        insertProcedures.setInt( index++, m_instanceId);
                        insertProcedures.setTimestamp( index++, new Timestamp(procedureResults.getLong("TIMESTAMP")));
                    } else {
                        index += 2;
                    }
                    insertProcedures.setLong( index++, procedureResults.getLong("HOST_ID"));
                    insertProcedures.setString( index++, procedureResults.getString("HOSTNAME"));
                    insertProcedures.setLong( index++, procedureResults.getLong("SITE_ID"));
                    insertProcedures.setString( index++, procedureResults.getString("PROCEDURE"));
                    insertProcedures.setLong( index++, procedureResults.getLong("INVOCATIONS"));
                    insertProcedures.setLong( index++, procedureResults.getLong("TIMED_INVOCATIONS"));
                    insertProcedures.setLong( index++, procedureResults.getLong("AVG_EXECUTION_TIME"));
                    insertProcedures.setLong( index++, procedureResults.getLong("MIN_EXECUTION_TIME"));
                    insertProcedures.setLong( index++, procedureResults.getLong("MAX_EXECUTION_TIME"));
                    insertProcedures.setLong( index++, procedureResults.getLong("ABORTS"));
                    insertProcedures.setLong( index++, procedureResults.getLong("FAILURES"));
                    insertProcedures.addBatch();
                }
            } finally {
                try {
                    insertProcedures.executeBatch();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
