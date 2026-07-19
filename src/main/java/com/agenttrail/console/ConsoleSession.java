package com.agenttrail.console;

import com.agenttrail.loop.AgentLoop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;

public class ConsoleSession {

    private final AgentLoop agentLoop;

    public ConsoleSession(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    public void run(BufferedReader input, PrintStream output) throws IOException {
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                output.println("Bye.");
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            try {
                output.println(agentLoop.run(line));
            } catch (RuntimeException e) {
                output.println("Error: " + e.getMessage());
            }
        }
    }
}
