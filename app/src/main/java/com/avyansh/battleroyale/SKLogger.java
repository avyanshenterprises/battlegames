package com.avyansh.battleroyale;

import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import com.avyansh.battleroyale.CodewareApplication;

/**
 * Reads this app's logcat on a background thread and broadcasts every line to the Codeware Studio
 * Logcat reader.
 *
 * Usage:
 * - Call `SKLogger.startLogging()` to begin logging.
 * - Call `SKLogger.stopLogging()` to stop logging.
 * - Use `SKLogger.broadcastLog(String)` to manually send a debug log message.
 */
public class SKLogger {
    private static final int MAX_RESTARTS = 3;
    /** Incremented by every start and stop; a reader thread only acts while it is the current one. */
    private static int generation = 0;
    private static boolean running = false;
    private static Process logcatProcess;
    private static int restarts = 0;

    private static Thread newLoggerThread(final int myGeneration) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean endedByItself = false;
                Process process = null;
                try {
                    Process clear = Runtime.getRuntime().exec("logcat -c");
                    clear.waitFor();
                    clear.getInputStream().close();
                    clear.getErrorStream().close();
                    clear.getOutputStream().close();

                    process = Runtime.getRuntime().exec("logcat");
                    synchronized (SKLogger.class) {
                        if (generation != myGeneration) {
                            process.destroy();
                            return;
                        }
                        logcatProcess = process;
                    }
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    try {
                        String line;
                        while (isCurrent(myGeneration) && (line = reader.readLine()) != null) {
                            broadcastLog(line);
                        }
                        endedByItself = isCurrent(myGeneration);
                    } finally {
                        try {
                            reader.close();
                        } catch (IOException ignored) {
                        }
                    }
                } catch (IOException e) {
                    if (isCurrent(myGeneration)) {
                        broadcastLog("Logger error: " + e.getMessage());
                        endedByItself = true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (process != null) process.destroy();
                }

                boolean restart = false;
                synchronized (SKLogger.class) {
                    if (generation != myGeneration) return; // a newer start/stop owns the state
                    running = false;
                    logcatProcess = null;
                    if (endedByItself && restarts < MAX_RESTARTS) {
                        restarts++;
                        restart = true;
                    }
                }
                if (restart) {
                    broadcastLog("Logger got killed. Restarting.");
                    startLogging();
                } else {
                    broadcastLog("Logger stopped.");
                }
            }
        }, "SKLogger");
        thread.setDaemon(true);
        return thread;
    }

    private static synchronized boolean isCurrent(int myGeneration) {
        return running && generation == myGeneration;
    }

    public static void startLogging() {
        int myGeneration;
        synchronized (SKLogger.class) {
            if (running) {
                myGeneration = -1;
            } else {
                running = true;
                myGeneration = ++generation;
            }
        }
        if (myGeneration == -1) {
            broadcastLog("Logger already running");
            return;
        }
        newLoggerThread(myGeneration).start();
    }

    public static void stopLogging() {
        boolean wasRunning;
        Process process = null;
        synchronized (SKLogger.class) {
            wasRunning = running;
            if (running) {
                running = false;
                generation++; // the reader thread sees it is no longer current and exits quietly
                process = logcatProcess;
                logcatProcess = null;
            }
        }
        if (!wasRunning) {
            broadcastLog("Logger not running");
            return;
        }
        if (process != null) process.destroy();
        broadcastLog("Stopping logger by user request.");
    }

    public static void broadcastLog(String log) {
        Context context = CodewareApplication.getContext();
        if (context == null || log == null) return;

        Intent intent = new Intent();
        intent.setAction("codeware.intent.action.NEW_DEBUG_LOG");
        intent.putExtra("log", log);
        intent.putExtra("packageName", context.getPackageName());
        context.sendBroadcast(intent);
    }
}
