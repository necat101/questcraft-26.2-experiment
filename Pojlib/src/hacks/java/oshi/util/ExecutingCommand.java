/*
 * Android-safe OSHI command shim for QuestCraft.
 *
 * OSHI's normal Linux backend probes desktop command line tools such as
 * lsb_release, getconf, uname, lspci, and route. Those probes are not useful on
 * Quest and can be fragile under the bundled JVM on Android, so this class is
 * placed before oshi-core on the classpath and returns small deterministic
 * answers instead of spawning processes.
 */
package oshi.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ExecutingCommand {

    private ExecutingCommand() {
    }

    public static List<String> runNative(String cmdToRun) {
        if (cmdToRun == null || cmdToRun.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return runNative(cmdToRun.trim().split(" "));
    }

    public static List<String> runNative(String[] cmdToRunWithArgs) {
        return runNative(cmdToRunWithArgs, null);
    }

    public static List<String> runNative(String[] cmdToRunWithArgs, String[] envp) {
        if (cmdToRunWithArgs == null || cmdToRunWithArgs.length == 0 || cmdToRunWithArgs[0] == null) {
            return Collections.emptyList();
        }

        String command = cmdToRunWithArgs[0];
        if ("getconf".equals(command) && cmdToRunWithArgs.length > 1) {
            if ("CLK_TCK".equals(cmdToRunWithArgs[1])) {
                return Collections.singletonList("100");
            }
            if ("PAGE_SIZE".equals(cmdToRunWithArgs[1]) || "PAGESIZE".equals(cmdToRunWithArgs[1])) {
                return Collections.singletonList("4096");
            }
        }

        if ("uname".equals(command)) {
            if (cmdToRunWithArgs.length > 1) {
                if ("-o".equals(cmdToRunWithArgs[1])) {
                    return Collections.singletonList("Android");
                }
                if ("-m".equals(cmdToRunWithArgs[1])) {
                    return Collections.singletonList("aarch64");
                }
                if ("-n".equals(cmdToRunWithArgs[1])) {
                    return Collections.singletonList("Quest");
                }
            }
            return Collections.singletonList("Linux");
        }

        if ("lsb_release".equals(command)) {
            return Arrays.asList(
                    "Distributor ID:\tAndroid",
                    "Description:\tAndroid release QuestCraft",
                    "Release:\t" + System.getProperty("os.version", "Android"),
                    "Codename:\tQuest"
            );
        }

        if ("id".equals(command) && cmdToRunWithArgs.length > 1 && "-u".equals(cmdToRunWithArgs[1])) {
            return Collections.singletonList("2000");
        }

        return Collections.emptyList();
    }

    public static String getFirstAnswer(String cmd2launch) {
        return getAnswerAt(cmd2launch, 0);
    }

    public static String getAnswerAt(String cmd2launch, int answerIdx) {
        List<String> output = runNative(cmd2launch);
        if (answerIdx >= 0 && answerIdx < output.size()) {
            return output.get(answerIdx);
        }
        return "";
    }
}
