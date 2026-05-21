package com.wish.models;

import lombok.Getter;

public enum AgentState {
    IDLE("idle"), RUNNING("running"), FINISHED("finished"), ERROR("error");

    @Getter
    private final String state;

    AgentState(String state) {
        this.state = state;
    }
}
