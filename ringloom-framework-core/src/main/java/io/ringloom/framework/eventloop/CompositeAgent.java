// SPDX-License-Identifier: Apache-2.0
package io.ringloom.framework.eventloop;

import java.util.Arrays;
import java.util.Objects;

public final class CompositeAgent implements Agent {
    private final Agent[] agents;
    private final String name;

    public CompositeAgent(String name, Agent... agents) {
        this.name = Objects.requireNonNullElse(name, "composite");
        this.agents = Objects.requireNonNull(agents, "agents").clone();
        for (Agent agent : this.agents) {
            Objects.requireNonNull(agent, "agent");
        }
    }

    @Override
    public int doWork() {
        int total = 0;
        for (Agent agent : agents) {
            int work = agent.doWork();
            if (work > 0 && Integer.MAX_VALUE - total < work) {
                total = Integer.MAX_VALUE;
            } else {
                total += Math.max(0, work);
            }
        }
        return total;
    }

    @Override
    public void onStart() {
        for (Agent agent : agents) {
            agent.onStart();
        }
    }

    @Override
    public void onClose() {
        for (int i = agents.length - 1; i >= 0; i--) {
            agents[i].onClose();
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name + Arrays.toString(agents);
    }
}
