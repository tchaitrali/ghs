package usi.si.seart.io;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import usi.si.seart.exception.TerminalExecutionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * A useful wrapper for managing the lifecycle of terminal processes.
 *
 * @author Ozren Dabić
 */
public class ExternalProcess {

    private final ProcessBuilder processBuilder;

    /**
     * Prepares a new terminal command.
     *
     * @param command Command and arguments.
     */
    public ExternalProcess(@NotNull String... command) {
        this(Paths.get("").toAbsolutePath(), Map.of(), command);
    }

    /**
     * Prepares a new terminal command.
     *
     * @param workdir The working directory in which the command will be executed.
     * @param command Command and arguments.
     */
    public ExternalProcess(@NotNull Path workdir, @NotNull String... command) {
        this(workdir, Map.of(), command);
    }

    /**
     * Prepares a new terminal command.
     *
     * @param environment Environment settings for the executing process.
     * @param command Command and arguments.
     */
    public ExternalProcess(@NotNull Map<String, String> environment, @NotNull String... command) {
        this(Paths.get("").toAbsolutePath(), environment, command);
    }

    /**
     * Prepares a new terminal command.
     *
     * @param workdir The working directory in which the command will be executed.
     * @param environment Environment settings for the executing process.
     * @param command Command and arguments.
     */
    public ExternalProcess(
            @NotNull Path workdir,
            @NotNull Map<String, String> environment,
            @NotNull String... command
    ) {
        if (!Files.isDirectory(workdir))
            throw new IllegalArgumentException("Specified path is not a directory!");
        Objects.requireNonNull(environment, "Environment settings must not be null!");
        Objects.requireNonNull(command, "Command and arguments must not be null!");

        processBuilder = new ProcessBuilder();
        processBuilder.environment().putAll(environment);
        processBuilder.directory(workdir.toFile());
        processBuilder.command(command);
    }

    /**
     * @return the terminal command.
     */
    public String getCommand() {
        return processBuilder.command().stream().collect(Collectors.joining(" "));
    }

    /**
     * Execute the prepared terminal command.
     * Default maximum runtime is 60 seconds.
     *
     * @return The execution {@code Result}.
     * @throws TimeoutException if the command times out.
     * @throws InterruptedException if the command is interrupted.
     */
    public Result execute() throws TimeoutException, InterruptedException {
        return execute(60, TimeUnit.SECONDS);
    }

    /**
     * Execute the prepared terminal command.
     * This variant allows you to specify the
     * maximum runtime.
     *
     * @param timeout the maximum time to wait.
     * @param unit the time unit of the {@code timeout} argument.
     * @return The execution {@code Result}.
     * @throws TimeoutException if the command times out.
     * @throws InterruptedException if the command is interrupted.
     */
    public Result execute(long timeout, TimeUnit unit) throws TimeoutException, InterruptedException {
        try {
            Process process = processBuilder.start();
            boolean exited = process.waitFor(timeout, unit);
            if (exited) {
                int code = process.exitValue();
                String stdOut = IOUtils.toString(process.getInputStream(), StandardCharsets.UTF_8);
                String stdErr = IOUtils.toString(process.getErrorStream(), StandardCharsets.UTF_8);
                return new Result(code, stdOut, stdErr);
            } else {
                while (process.isAlive()) process.destroyForcibly();
                String message = "Timed out while executing terminal command: " + getCommand();
                throw new TimeoutException(message);
            }
        } catch (IOException ex) {
            String message = "Exception occurred while executing terminal command: " + getCommand();
            throw new TerminalExecutionException(message, ex);
        }
    }

    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
    public static final class Result {

        int code;
        String stdOut;
        String stdErr;

        public boolean succeeded() {
            return code == 0;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            if (succeeded() && StringUtils.isNotBlank(stdOut)) {
                builder.append(stdOut).append('\n');
            } else if (!succeeded() && StringUtils.isNotBlank(stdErr)) {
                builder.append(stdErr).append('\n');
            }
            builder.append(getTerminalLine());
            return builder.toString();
        }

        private String getTerminalLine() {
            return "Process finished with exit code " + code;
        }
    }
}
