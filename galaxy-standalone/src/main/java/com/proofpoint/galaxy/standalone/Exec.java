package com.proofpoint.galaxy.standalone;

import com.google.common.base.Preconditions;
import com.proofpoint.galaxy.shared.SlotStatusRepresentation;
import jnr.posix.FileStat;
import jnr.posix.POSIX;
import jnr.posix.util.Platform;

import static com.google.common.base.Objects.firstNonNull;
import static java.lang.System.getenv;

public class Exec
{
    public static String PS = Platform.IS_WINDOWS ? ";" : ":";

    public static void execLocal(SlotStatusRepresentation slot, String command)
    {
        command = firstNonNull(command, "exec -l $0");

        String path = firstNonNull(slot.getInstallPath(), "$HOME");

        StringBuilder localCommand = new StringBuilder();
        localCommand.append("cd \"").append(path).append("\"; ").append(command);

        execLocal(localCommand.toString());
    }

    public static void execLocal(String command)
    {
        POSIX posix = POSIXFactory.getPOSIX();
        String shell = firstNonNull(getenv("SHELL"), firstNonNull(findFileInPath(posix, "bash", null), "/bin/bash"));

        String[] args;
        if (command == null) {
            args = new String[]{shell, "-l"};
        }
        else {
            args = new String[]{shell, "-c", shellQuote(command)};
        }
        posix.execv(shell, args);
    }

    public static void execRemote(SlotStatusRepresentation slot, String command)
    {
        String host = slot.getHost();
        command = firstNonNull(command, "exec -l $0");

        Preconditions.checkNotNull(host, "host is null");
        String path = firstNonNull(slot.getInstallPath(), "$HOME");

        StringBuilder remoteCommandBuilder = new StringBuilder();
        remoteCommandBuilder.append("cd \"").append(path).append("\"; ").append(command);

        execRemote(host, remoteCommandBuilder.toString());
    }

    public static void execRemote(String host, String command)
    {
        POSIX posix = POSIXFactory.getPOSIX();
        String ssh = firstNonNull(getenv("GALAXY_SSH_COMMAND"), firstNonNull(findFileInPath(posix, "ssh", null), "/usr/bin/ssh"));

        String[] args;
        if (command == null) {
            args = new String[]{ssh, host};
        }
        else {
            args = new String[]{ssh, host, "-t", shellQuote(command)};
        }

        posix.execv(ssh, args);
    }

    public static String shellQuote(String command)
    {
        return command.replace("'", "\\\'");
    }

    public static String findFileInPath(POSIX posix, String name, String path)
    {
        if (path == null || path.length() == 0) {
            path = System.getenv("PATH");
        }

        // MRI sets up a bogus path which seems like it would violate security
        // if nothing else since if I don't have /usr/bin in my path but I end
        // up executing it anyways???  Returning original name and hoping for
        // best.
        if (path == null || path.length() == 0) {
            return name;
        }

        return findFileCommon(posix, name, path, true);
    }

    public static String findFileCommon(POSIX posix, String name, String path, boolean executableOnly)
    {
        // No point looking for nothing...
        if (name == null || name.length() == 0) {
            return name;
        }

        int length = name.length();
        if (!Platform.IS_WINDOWS) {


            if (length > 1 && Character.isLetter(name.charAt(0)) && name.charAt(1) == '/') {
                if (isMatch(posix, executableOnly, name)) {
                    return name;
                }
                else {
                    return null;
                }
            }

            String[] paths = path.split(PS);
            for (String currentPath : paths) {
                int currentPathLength = currentPath.length();

                if (currentPath == null || currentPathLength == 0) {
                    continue;
                }

                if (!currentPath.endsWith("/") && !currentPath.endsWith("\\")) {
                    currentPath += "/";
                }

                String filename = currentPath + name;

                if (isMatch(posix, executableOnly, filename)) {
                    return filename;
                }
            }
        }

        return null;
    }

    public static boolean isMatch(POSIX posix, boolean executableOnly, String filename)
    {
        FileStat stat = posix.allocateStat();
        int value = posix.libc().stat(filename, stat);
        if (value >= 0) {
            if (!executableOnly) {
                return true;
            }

            if (!stat.isDirectory() && stat.isExecutable()) {
                return true;
            }
        }
        return false;
    }
}
