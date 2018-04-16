/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
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

package org.voltdb.calciteadapter.rules.physical;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.voltdb.calciteadapter.rel.logical.VoltDBLogicalAggregate;
import org.voltdb.calciteadapter.rel.logical.VoltDBLogicalRel;
import org.voltdb.calciteadapter.rel.physical.VoltDBAggregate;
import org.voltdb.calciteadapter.rel.physical.VoltDBPhysicalRel;


public class VoltDBPhysicalAggregateRule extends RelOptRule {

    public static final VoltDBPhysicalAggregateRule INSTANCE = new VoltDBPhysicalAggregateRule();

    private VoltDBPhysicalAggregateRule() {
        super(operand(VoltDBLogicalAggregate.class, VoltDBLogicalRel.VOLTDB_LOGICAL, any()));
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        VoltDBLogicalAggregate aggregate = call.rel(0);
        RelTraitSet convertedTraits = aggregate.getTraitSet().replace(VoltDBPhysicalRel.VOLTDB_PHYSICAL);
        RelNode input = aggregate.getInput();
        RelNode convertedInput = convert(input, input.getTraitSet().replace(VoltDBPhysicalRel.VOLTDB_PHYSICAL));
        call.transformTo(VoltDBAggregate.create(
                aggregate.getCluster(),
                convertedTraits,
                convertedInput,
                aggregate.indicator,
                aggregate.getGroupSet(),
                aggregate.getGroupSets(),
                aggregate.getAggCallList(),
                null));
    }
}
