package usi.si.seart.service;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import usi.si.seart.analysis.ClonedRepo;
import usi.si.seart.analysis.TerminalExecution;
import usi.si.seart.exception.CloneException;
import usi.si.seart.exception.TerminalExecutionException;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Service responsible for cloning git repositories into a temporary folder.
 */
@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
@NoArgsConstructor(onConstructor_ = @Autowired)
public class GitRepoClonerService {

    // Prefix of the temporary folders where the repositories get cloned in
    @Value("${app.crawl.analysis.folderprefix}")
    String repoFolderPrefix;


    /**
     * Clones a git repository into a temporary folder and returns a handle for the cloned repository.
     *
     * @param gitRepoURL the URL of the git repository.
     * @return the handle of the cloned repository.
     * @throws CloneException if the cloning operation was unsuccessful.
     */
    public Future<ClonedRepo> cloneRepo(URL gitRepoURL) throws CloneException {
        Path tempRepoDir;
        try {
            tempRepoDir = Files.createTempDirectory(repoFolderPrefix);
        } catch (IOException e) {
            throw new CloneException(e);
        }

        TerminalExecution cloneProcess = new TerminalExecution(
                tempRepoDir, "git clone --depth 1", gitRepoURL.toString(), tempRepoDir.toString()
        );
        try {
            log.trace("Cloning repository '{}' ...",gitRepoURL);
            cloneProcess.start().waitSuccessfulExit();
            // clone process did either not start or failed
        } catch (TerminalExecutionException e) {
            cloneProcess.stop();
            try (Stream<Path> walk = Files.walk(tempRepoDir)) {
                // Deletes the temporary folder
                walk.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (IOException ex) {
                throw new CloneException(ex);
            }
            throw new CloneException("'git clone' process did not start/exit successfully", e);
        }
        return new AsyncResult<>(new ClonedRepo(tempRepoDir));
    }

    /**
     * Deleted leftover temporary folders on startup and on shutdown.
     */
    @EventListener({ApplicationReadyEvent.class})
    public void cleanupAllTempFolders() {
        try {
            new TerminalExecution(
                    Files.createTempDirectory(repoFolderPrefix).getParent(), "rm -rf ", repoFolderPrefix + "*"
            ).start().waitSuccessfulExit();
        } catch (Exception e) {
            log.error("Failed to cleanup cloned repository folders", e);
            return;
        }
        log.info("Successfully cleaned up repository folder");
    }
}