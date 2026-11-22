package com.ayan7601.blazed.ipc;

import com.google.gson.JsonObject;

public record Packet(Opcode opcode, JsonObject data) {
}
