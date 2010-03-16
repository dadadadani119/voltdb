/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.benchmark.tpcc;

import org.voltdb.types.TimestampType;
import org.voltdb.client.ClientResponse;
import org.voltdb.benchmark.ClientMain;
import org.voltdb.benchmark.Clock;
import org.voltdb.benchmark.Verification;
import org.voltdb.benchmark.Verification.Expression;
import org.voltdb.client.ProcedureCallback;
import org.voltdb.compiler.VoltProjectBuilder;
import org.voltdb.client.Client;
import org.voltdb.client.NoConnectionsException;
import org.voltdb.types.ExpressionType;

import java.io.IOException;

public class TPCCClient extends org.voltdb.benchmark.ClientMain
implements TPCCSimulation.ProcCaller {
    final TPCCSimulation m_tpccSim;
    final TPCCSimulation m_tpccSim2;
    private final ScaleParameters m_scaleParams;

    /** Complies with our benchmark client remote controller scheme */
    public static void main(String args[]) {
        org.voltdb.benchmark.ClientMain.main(TPCCClient.class, args, false);
    }

    public TPCCClient(
            Client client,
            RandomGenerator generator,
            Clock clock,
            ScaleParameters params)
    {
        this(client, generator, clock, params, 0.0d);
    }

    public TPCCClient(
            Client client,
            RandomGenerator generator,
            Clock clock,
            ScaleParameters params,
            double skewFactor)
    {
        super(client);
        m_scaleParams = params;
        m_tpccSim = new TPCCSimulation(this, generator, clock, m_scaleParams, false, skewFactor);
        m_tpccSim2 = new TPCCSimulation(this, generator, clock, m_scaleParams, false, skewFactor);
    }

    /** Complies with our benchmark client remote controller scheme */
    public TPCCClient(String args[]) {
        super(args);
        /*
         * Input parameters:
         *   warehouses=#
         *   scalefactor=#
         *   skewfactor=#
         */

        // default values
        int warehouses = 1;
        double scalefactor = 1.0;
        double skewfactor = 0.0;

        // scan the inputs once to read everything but host names
        for (String arg : args) {
            String[] parts = arg.split("=",2);
            if (parts.length == 1) {
                continue;
            }
            else if (parts[1].startsWith("${")) {
                continue;
            }
            else if (parts[0].equals("warehouses")) {
                warehouses = Integer.parseInt(parts[1]);
            }
            else if (parts[0].equals("scalefactor")) {
                scalefactor = Double.parseDouble(parts[1]);
            }
            else if (parts[0].equals("skewfactor")) {
                skewfactor = Double.parseDouble(parts[1]);
            }
        }

        // makeForRun requires the value cLast from the load generator in
        // order to produce a valid generator for the run. Thus the sort
        // of weird eat-your-own ctor pattern.
        RandomGenerator.NURandC base_loadC = new RandomGenerator.NURandC(0,0,0);
        RandomGenerator.NURandC base_runC = RandomGenerator.NURandC.makeForRun(
                new RandomGenerator.Implementation(0), base_loadC);
        RandomGenerator rng = new RandomGenerator.Implementation(0);
        rng.setC(base_runC);

        RandomGenerator.NURandC base_loadC2 = new RandomGenerator.NURandC(0,0,0);
        RandomGenerator.NURandC base_runC2 = RandomGenerator.NURandC.makeForRun(
                new RandomGenerator.Implementation(0), base_loadC2);
        RandomGenerator rng2 = new RandomGenerator.Implementation(0);
        rng.setC(base_runC2);

        m_scaleParams =
            ScaleParameters.makeWithScaleFactor(warehouses, scalefactor);
        m_tpccSim =
            new TPCCSimulation(this, rng, new Clock.RealTime(), m_scaleParams, false, skewfactor);
        m_tpccSim2 =
            new TPCCSimulation(this, rng2, new Clock.RealTime(), m_scaleParams, false, skewfactor);

        // Set up checking
        buildConstraints();

        //m_sampler = new VoltSampler(20, "tpcc-cliet-sampling");
    }

    @Override
    protected boolean useHeavyweightClient() {
        return true;
    }

    protected void buildConstraints() {
        Expression constraint = null;

        // WAREHOUSE table
        Expression w_id = Verification.inRange("W_ID", (short) 1,
                                               (short) (m_scaleParams.warehouses * 2));
        Expression w_tax = Verification.inRange("W_TAX", Constants.MIN_TAX,
                                                Constants.MAX_TAX);
        Expression warehouse = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                                        w_id, w_tax);

        // DISTRICT table
        Expression d_id = Verification.inRange("D_ID", (byte) 1,
                                               (byte) m_scaleParams.districtsPerWarehouse);
        Expression d_w_id = Verification.inRange("D_W_ID", (short) 1,
                                                 (short) (m_scaleParams.warehouses * 2));
        Expression d_next_o_id = Verification.inRange("D_NEXT_O_ID", 1, 10000000);
        Expression d_tax = Verification.inRange("D_TAX", Constants.MIN_TAX,
                                                Constants.MAX_TAX);
        Expression district = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                                       d_id, d_w_id, d_next_o_id, d_tax);

        // CUSTOMER table
        Expression c_id = Verification.inRange("C_ID", 1,
                                               m_scaleParams.customersPerDistrict);
        Expression c_d_id = Verification.inRange("C_D_ID", (byte) 1,
                                                 (byte) m_scaleParams.districtsPerWarehouse);
        Expression c_w_id = Verification.inRange("C_W_ID", (short) 1,
                                                 (short) (m_scaleParams.warehouses * 2));
        Expression c_discount = Verification.inRange("C_DISCOUNT", Constants.MIN_DISCOUNT,
                                                     Constants.MAX_DISCOUNT);
        Expression c_credit =
            Verification.conjunction(ExpressionType.CONJUNCTION_OR,
                                     Verification.compareWithConstant(ExpressionType.COMPARE_EQUAL,
                                                                      "C_CREDIT",
                                                                      Constants.GOOD_CREDIT),
                                     Verification.compareWithConstant(ExpressionType.COMPARE_EQUAL,
                                                                      "C_CREDIT",
                                                                      Constants.BAD_CREDIT));
        Expression customer = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                                       c_id, c_d_id, c_w_id, c_discount, c_credit);

        // CUSTOMER_NAME table
        Expression customer_name = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                                            c_id, c_d_id, c_w_id);

        // HISTORY table
        Expression h_c_id = Verification.inRange("H_C_ID", 1,
                                                 m_scaleParams.customersPerDistrict);
        Expression h_c_d_id = Verification.inRange("H_C_D_ID", (byte) 1,
                                                   (byte) m_scaleParams.districtsPerWarehouse);
        Expression h_c_w_id = Verification.inRange("H_C_W_ID", (short) 1,
                                                   (short) (m_scaleParams.warehouses * 2));
        Expression h_d_id = Verification.inRange("H_D_ID", (byte) 1,
                                                 (byte) m_scaleParams.districtsPerWarehouse);
        Expression h_w_id = Verification.inRange("H_W_ID", (short) 1,
                                                 (short) (m_scaleParams.warehouses * 2));
        Expression history = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                                      h_c_id, h_c_d_id, h_c_w_id, h_d_id, h_w_id);

        // NEW_ORDER table
        Expression no_o_id = Verification.inRange("NO_O_ID", 1, 10000000);
        Expression no_d_id = Verification.inRange("NO_D_ID", (byte) 1,
                                                  (byte) m_scaleParams.districtsPerWarehouse);
        Expression no_w_id = Verification.inRange("NO_W_ID", (short) 1,
                                                  (short) (m_scaleParams.warehouses * 2));
        Expression new_order = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                                        no_o_id, no_d_id, no_w_id);

        // ORDERS table
        Expression o_id = Verification.inRange("O_ID", 1, 10000000);
        Expression o_c_id = Verification.inRange("O_C_ID", 1,
                                                 m_scaleParams.customersPerDistrict);
        Expression o_d_id = Verification.inRange("O_D_ID", (byte) 1,
                                                 (byte) m_scaleParams.districtsPerWarehouse);
        Expression o_w_id = Verification.inRange("O_W_ID", (short) 1,
                                                 (short) (m_scaleParams.warehouses * 2));
        Expression o_carrier_id =
            Verification.conjunction(ExpressionType.CONJUNCTION_OR,
                                     Verification.inRange("O_CARRIER_ID",
                                                          Constants.MIN_CARRIER_ID,
                                                          Constants.MAX_CARRIER_ID),
                                     Verification.compareWithConstant(ExpressionType.COMPARE_EQUAL,
                                                                      "O_CARRIER_ID",
                                                                      (int) Constants.NULL_CARRIER_ID));
        Expression orders = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                                     o_id, o_c_id, o_d_id, o_w_id, o_carrier_id);

        // ORDER_LINE table
        Expression ol_o_id = Verification.inRange("OL_O_ID", 1, 10000000);
        Expression ol_d_id = Verification.inRange("OL_D_ID", (byte) 1,
                                                  (byte) m_scaleParams.districtsPerWarehouse);
        Expression ol_w_id = Verification.inRange("OL_W_ID", (short) 1,
                                                  (short) (m_scaleParams.warehouses * 2));
        Expression ol_number = Verification.inRange("OL_NUMBER", 1,
                                                    Constants.MAX_OL_CNT);
        Expression ol_i_id = Verification.inRange("OL_I_ID", 1, m_scaleParams.items);
        Expression ol_supply_w_id = Verification.inRange("OL_SUPPLY_W_ID", (short) 1,
                                                         (short) (m_scaleParams.warehouses * 2));
        Expression ol_quantity = Verification.inRange("OL_QUANTITY", 0,
                                                      Constants.MAX_OL_QUANTITY);
        Expression ol_amount = Verification.inRange("OL_AMOUNT",
                                                    0,
                                                    Constants.MAX_PRICE * Constants.MAX_OL_QUANTITY);
        Expression order_line = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                                         ol_o_id, ol_d_id, ol_w_id, ol_number,
                                                         ol_i_id, ol_supply_w_id, ol_quantity,
                                                         ol_amount);

        // ITEM table
        Expression i_id = Verification.inRange("I_ID", 1, m_scaleParams.items);
        Expression i_im_id = Verification.inRange("I_IM_ID", Constants.MIN_IM,
                                                  Constants.MAX_IM);
        Expression i_price = Verification.inRange("I_PRICE", Constants.MIN_PRICE,
                                                  Constants.MAX_PRICE);
        Expression item = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                                   i_id, i_im_id, i_price);

        // STOCK table
        Expression s_i_id = Verification.inRange("S_I_ID", 1, m_scaleParams.items);
        Expression s_w_id = Verification.inRange("S_W_ID", (short) 1,
                                                 (short) (m_scaleParams.warehouses * 2));
        Expression s_quantity = Verification.inRange("S_QUANTITY", Constants.MIN_QUANTITY,
                                                     Constants.MAX_QUANTITY);
        Expression stock = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                                    s_i_id, s_w_id, s_quantity);

        // Delivery (no need to check 'd_id', it's systematically generated)
        constraint = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                              d_id, o_id);
        addConstraint(Constants.DELIVERY, 0, constraint);

        // New Order table 0
        constraint = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                              c_discount, c_credit);
        addConstraint(Constants.NEWORDER, 0, constraint);
        // New Order table 1
        constraint =
            Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                     d_next_o_id,
                                     w_tax, d_tax,
                                     Verification.compareWithConstant(ExpressionType.COMPARE_GREATERTHAN,
                                                                      "total", 0.0));
        addConstraint(Constants.NEWORDER, 1, constraint);
        // New Order table 2
        constraint = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                              s_quantity,
                                              i_price,
                                              ol_amount);
        addConstraint(Constants.NEWORDER, 2, constraint);

        // Order Status table 0
        addConstraint(Constants.ORDER_STATUS_BY_ID, 0, c_id);
        addConstraint(Constants.ORDER_STATUS_BY_NAME, 0, c_id);
        // Order Status table 1
        constraint = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                              o_id, o_carrier_id);
        addConstraint(Constants.ORDER_STATUS_BY_ID, 1, constraint);
        addConstraint(Constants.ORDER_STATUS_BY_NAME, 1, constraint);
        // Order Status table 2
        constraint = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                              ol_supply_w_id, ol_i_id,
                                              ol_quantity, ol_amount);
        addConstraint(Constants.ORDER_STATUS_BY_ID, 2, constraint);
        addConstraint(Constants.ORDER_STATUS_BY_NAME, 2, constraint);

        // Payment
        constraint = Verification.conjunction(ExpressionType.CONJUNCTION_AND,
                                              c_id, c_discount);
        addConstraint(Constants.PAYMENT_BY_ID, 2, constraint);
        addConstraint(Constants.PAYMENT_BY_ID_C, 0, constraint);
        addConstraint(Constants.PAYMENT_BY_NAME, 2, constraint);
        addConstraint(Constants.PAYMENT_BY_NAME_C, 0, constraint);

        // slev
        constraint = Verification.compareWithConstant(ExpressionType.COMPARE_GREATERTHANOREQUALTO,
                                                      "C1",
                                                      0L);
        addConstraint(Constants.STOCK_LEVEL, 0, constraint);

        // Full table checks
        addConstraint("WAREHOUSE", 0, warehouse);
        addConstraint("DISTRICT", 0, district);
        addConstraint("CUSTOMER", 0, customer);
        addConstraint("CUSTOMER_NAME", 0, customer_name);
        addConstraint("HISTORY", 0, history);
        addConstraint("NEW_ORDER", 0, new_order);
        addConstraint("ORDERS", 0, orders);
        addConstraint("ORDER_LINE", 0, order_line);
        addConstraint("ITEM", 0, item);
        addConstraint("STOCK", 0, stock);

        // TODO Foreign key constraints
    }

    /**
     * Whether a message was queued when attempting the last invocation.
     */
    private boolean m_queuedMessage = false;

    /*
     * callXXX methods should spin on backpressure barrier until they successfully queue rather then
     * setting m_queuedMessage and returning immediately.
     */
    private boolean m_blockOnBackpressure = true;

    @Override
    protected boolean runOnce() throws NoConnectionsException {
        m_blockOnBackpressure = false;
        // will send procedures to first connection w/o backpressure
        // if all connections have backpressure, will round robin across
        // busy servers (but the loop will spend more time running the
        // network below.)
        try {
            m_tpccSim.doOne();
            return m_queuedMessage;
        } catch (IOException e) {
            throw (NoConnectionsException)e;
        }
    }

    /**
     * Hint used when constructing the Client to control the size of buffers allocated for message
     * serialization
     * Set to 512 because neworder tops out around that size
     * @return
     */
    @Override
    protected int getExpectedOutgoingMessageSize() {
        return 256;
    }

    @Override
    protected void runLoop() throws NoConnectionsException {
        m_blockOnBackpressure = true;
        if (Runtime.getRuntime().availableProcessors() > 4) {
            new Thread() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            m_tpccSim2.doOne();
                        }
                    } catch (IOException e) {
                    }
                }
            }.start();
        }
        try {
            while (true) {
                // will send procedures to first connection w/o backpressure
                // if all connections have backpressure, will round robin across
                // busy servers (but the loop will spend more time running the
                // network below.)
                m_tpccSim.doOne();
            }
        } catch (IOException e) {
            throw (NoConnectionsException)e;
        }
    }

    // Delivery
    class DeliveryCallback extends ProcedureCallback {
        @Override
        public void clientCallback(ClientResponse clientResponse) {
            boolean status = checkTransaction(Constants.DELIVERY, clientResponse, false, false);
            assert status;
            if (status && clientResponse.getResults()[0].getRowCount()
                    != m_scaleParams.districtsPerWarehouse) {
                System.err.println(
                        "Only delivered from "
                        + clientResponse.getResults()[0].getRowCount()
                        + " districts.");
            }
            m_counts[TPCCSimulation.Transaction.DELIVERY.ordinal()].incrementAndGet();
        }
    }

    @Override
    public void callDelivery(short w_id, int carrier, TimestampType date) throws IOException {
        if (m_blockOnBackpressure) {
            final DeliveryCallback cb = new DeliveryCallback();
            while (!m_voltClient.callProcedure(cb,
                Constants.DELIVERY, w_id, carrier, date)) {
                try {
                    m_voltClient.backpressureBarrier();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        } else {
            m_queuedMessage = m_voltClient.callProcedure(new DeliveryCallback(),
                    Constants.DELIVERY, w_id, carrier, date);
        }
    }


    // NewOrder

    class NewOrderCallback extends ProcedureCallback {

        public NewOrderCallback(boolean rollback) {
            super();
            this.cbRollback = rollback;
        }

        @Override
        public void clientCallback(ClientResponse clientResponse) {
            boolean status = checkTransaction(Constants.NEWORDER, clientResponse, cbRollback, false);
            assert this.cbRollback || status;
            m_counts[TPCCSimulation.Transaction.NEW_ORDER.ordinal()].incrementAndGet();
        }

        private boolean cbRollback;
    }

    int randomIndex = 0;
    @Override
    public void callNewOrder(boolean rollback, Object... paramlist) throws IOException {
        if (m_blockOnBackpressure) {
            final NewOrderCallback cb = new NewOrderCallback(rollback);
            while (!m_voltClient.callProcedure( cb,
                Constants.NEWORDER, paramlist)) {
                try {
                    m_voltClient.backpressureBarrier();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        } else {
            m_queuedMessage = m_voltClient.callProcedure(new NewOrderCallback(rollback),
                    Constants.NEWORDER, paramlist);
        }
    }

    // Order status

    class VerifyBasicCallback extends ProcedureCallback {
        private final TPCCSimulation.Transaction m_transactionType;
        private final String m_procedureName;

        /**
         * A generic callback that does not credit a transaction. Some transactions
         * use two procedure calls - this counts as one transaction not two.
         */
        VerifyBasicCallback() {
            m_transactionType = null;
            m_procedureName = null;
        }

        /** A generic callback that credits for the transaction type passed. */
        VerifyBasicCallback(TPCCSimulation.Transaction transaction, String procName) {
            m_transactionType = transaction;
            m_procedureName = procName;
        }

        @Override
        public void clientCallback(ClientResponse clientResponse) {
            boolean abortExpected = false;
            if (m_procedureName != null && (m_procedureName.equals(Constants.ORDER_STATUS_BY_NAME)
                || m_procedureName.equals(Constants.ORDER_STATUS_BY_ID)))
                abortExpected = true;
            boolean status = checkTransaction(m_procedureName,
                                              clientResponse,
                                              abortExpected,
                                              false);
            assert status;
            if (m_transactionType != null) {
                m_counts[m_transactionType.ordinal()].incrementAndGet();
            }
        }
    }

    @Override
    public void callOrderStatus(String proc, Object... paramlist) throws IOException {
        if (m_blockOnBackpressure) {
            final VerifyBasicCallback cb = new VerifyBasicCallback(TPCCSimulation.Transaction.ORDER_STATUS,
                    proc);
            while (!m_voltClient.callProcedure( cb,
                                                                             proc, paramlist)) {
                try {
                    m_voltClient.backpressureBarrier();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        } else {
            m_queuedMessage = m_voltClient.callProcedure(new VerifyBasicCallback(TPCCSimulation.Transaction.ORDER_STATUS,
                                                                             proc),
                proc, paramlist);
        }
    }


    // Payment


    @Override
    public void callPaymentById(short w_id, byte d_id, double h_amount,
            short c_w_id, byte c_d_id, int c_id, TimestampType now) throws IOException {
       if (m_blockOnBackpressure) {
           if (m_scaleParams.warehouses > 1) {
               final VerifyBasicCallback cb = new VerifyBasicCallback();
               while(!m_voltClient.callProcedure( cb,
                      Constants.PAYMENT_BY_ID_W,
                      w_id, d_id, h_amount, c_w_id, c_d_id, c_id, now)) {
                   try {
                    m_voltClient.backpressureBarrier();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
               }
               final VerifyBasicCallback cb2 = new VerifyBasicCallback(TPCCSimulation.Transaction.PAYMENT,
                       Constants.PAYMENT_BY_ID_C);
               while (!m_voltClient.callProcedure( cb2,
                      Constants.PAYMENT_BY_ID_C,
                      w_id, d_id, h_amount, c_w_id, c_d_id, c_id, now)) {
                   try {
                    m_voltClient.backpressureBarrier();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
               }
          }
          else {
              final VerifyBasicCallback cb = new VerifyBasicCallback(TPCCSimulation.Transaction.PAYMENT,
                      Constants.PAYMENT_BY_ID);
               while (!m_voltClient.callProcedure( cb,
                      Constants.PAYMENT_BY_ID,
                      w_id, d_id, h_amount, c_w_id, c_d_id, c_id, now)) {
                   try {
                    m_voltClient.backpressureBarrier();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
               }
          }
       } else {
           if (m_scaleParams.warehouses > 1) {
                m_voltClient.callProcedure(new VerifyBasicCallback(),
                       Constants.PAYMENT_BY_ID_W,
                       w_id, d_id, h_amount, c_w_id, c_d_id, c_id, now);
                m_queuedMessage = m_voltClient.callProcedure(new VerifyBasicCallback(TPCCSimulation.Transaction.PAYMENT,
                                                                                     Constants.PAYMENT_BY_ID_C),
                       Constants.PAYMENT_BY_ID_C,
                       w_id, d_id, h_amount, c_w_id, c_d_id, c_id, now);
           }
           else {
                m_queuedMessage = m_voltClient.callProcedure(new VerifyBasicCallback(TPCCSimulation.Transaction.PAYMENT,
                                                                                     Constants.PAYMENT_BY_ID),
                       Constants.PAYMENT_BY_ID,
                       w_id, d_id, h_amount, c_w_id, c_d_id, c_id, now);
           }
       }
    }

    @Override
    public void callPaymentByName(short w_id, byte d_id, double h_amount,
            short c_w_id, byte c_d_id, byte[] c_last, TimestampType now) throws IOException {
        if (m_blockOnBackpressure) {
            if ((m_scaleParams.warehouses > 1) || (c_last != null)) {
                final VerifyBasicCallback cb = new VerifyBasicCallback();
                while(!m_voltClient.callProcedure(cb,
                        Constants.PAYMENT_BY_NAME_W, w_id, d_id, h_amount,
                        c_w_id, c_d_id, c_last, now)) {
                    try {
                        m_voltClient.backpressureBarrier();
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
                final VerifyBasicCallback cb2 = new VerifyBasicCallback(TPCCSimulation.Transaction.PAYMENT,
                        Constants.PAYMENT_BY_NAME_C);
                while(!m_voltClient.callProcedure( cb2,
                        Constants.PAYMENT_BY_NAME_C, w_id, d_id, h_amount,
                        c_w_id, c_d_id, c_last, now)) {
                    try {
                        m_voltClient.backpressureBarrier();
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }
            else {
                final VerifyBasicCallback cb = new VerifyBasicCallback(TPCCSimulation.Transaction.PAYMENT,
                        Constants.PAYMENT_BY_ID);
                while(!m_voltClient.callProcedure( cb,
                        Constants.PAYMENT_BY_ID, w_id,
                        d_id, h_amount, c_w_id, c_d_id, c_last, now)) {
                    try {
                        m_voltClient.backpressureBarrier();
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }
        } else {
            if ((m_scaleParams.warehouses > 1) || (c_last != null)) {
                m_voltClient.callProcedure(new VerifyBasicCallback(),
                        Constants.PAYMENT_BY_NAME_W, w_id, d_id, h_amount,
                        c_w_id, c_d_id, c_last, now);
                m_queuedMessage = m_voltClient.callProcedure(new VerifyBasicCallback(TPCCSimulation.Transaction.PAYMENT,
                                                                                     Constants.PAYMENT_BY_NAME_C),
                        Constants.PAYMENT_BY_NAME_C, w_id, d_id, h_amount,
                        c_w_id, c_d_id, c_last, now);
            }
            else {
                m_queuedMessage = m_voltClient.callProcedure(new VerifyBasicCallback(TPCCSimulation.Transaction.PAYMENT,
                                                                                     Constants.PAYMENT_BY_ID),
                        Constants.PAYMENT_BY_ID, w_id,
                        d_id, h_amount, c_w_id, c_d_id, c_last, now);
            }
        }
    }


    // StockLevel
    class StockLevelCallback extends ProcedureCallback {
        @Override
        public void clientCallback(ClientResponse clientResponse) {
            boolean status = checkTransaction(Constants.STOCK_LEVEL,
                                              clientResponse,
                                              false,
                                              false);
            assert status;
            m_counts[TPCCSimulation.Transaction.STOCK_LEVEL.ordinal()].incrementAndGet();
        }
      }

    @Override
    public void callStockLevel(short w_id, byte d_id, int threshold) throws IOException {
        final StockLevelCallback cb = new StockLevelCallback();
        while (!m_voltClient.callProcedure( cb, Constants.STOCK_LEVEL,
                w_id, d_id, threshold)) {
            try {
                m_voltClient.backpressureBarrier();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

    }

    class DumpStatisticsCallback extends ProcedureCallback {
        @Override
        public void clientCallback(ClientResponse clientResponse) {
            if (checkTransaction(null, clientResponse, false, false))
                System.err.println(clientResponse.getResults()[0]);
        }
    }

    public void dumpStatistics() throws IOException {
        m_queuedMessage = m_voltClient.callProcedure(new DumpStatisticsCallback(),
                "@Statistics", "procedure");
    }

    class ResetWarehouseCallback extends ProcedureCallback {
        @Override
        public void clientCallback(ClientResponse clientResponse) {
            if (checkTransaction(null, clientResponse, false, false))
                m_counts[TPCCSimulation.Transaction.RESET_WAREHOUSE.ordinal()].incrementAndGet();
        }
    }

    @Override
    public void callResetWarehouse(long w_id, long districtsPerWarehouse,
            long customersPerDistrict, long newOrdersPerDistrict)
    throws IOException {
        m_queuedMessage = m_voltClient.callProcedure(new ResetWarehouseCallback(),
              Constants.RESET_WAREHOUSE, w_id, districtsPerWarehouse,
              customersPerDistrict, newOrdersPerDistrict);
    }

    @Override
    protected String[] getTransactionDisplayNames() {
        String countDisplayNames[] = new String[TPCCSimulation.Transaction.values().length];
        for (int ii = 0; ii < TPCCSimulation.Transaction.values().length; ii++) {
            countDisplayNames[ii] = TPCCSimulation.Transaction.values()[ii].displayName;
        }
        return countDisplayNames;
    }

    /**
     * Retrieved via reflection by BenchmarkController
     */
    public static final Class<? extends VoltProjectBuilder> m_projectBuilderClass =
        TPCCProjectBuilder.class;
    /**
     * Retrieved via reflection by BenchmarkController
     */
    public static final Class<? extends ClientMain> m_loaderClass =
        org.voltdb.benchmark.tpcc.MultiLoader.class;
    /**
     * Retrieved via reflection by BenchmarkController
     */
    public static final String m_jarFileName = "tpcc.jar";

    @Override
    protected String getApplicationName() {
        return "TPC-C";
    }

    @Override
    protected String getSubApplicationName() {
        return "Client";
    }
}
