package usi.si.seart.job;

import com.google.common.base.Strings;
import com.google.common.collect.Range;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.convert.ConversionService;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import usi.si.seart.exception.MetadataCrawlingException;
import usi.si.seart.exception.UnsplittableRangeException;
import usi.si.seart.github.GitCommit;
import usi.si.seart.github.GitHubAPIConnector;
import usi.si.seart.model.GitRepo;
import usi.si.seart.model.Label;
import usi.si.seart.model.Language;
import usi.si.seart.model.Topic;
import usi.si.seart.model.join.GitRepoLanguage;
import usi.si.seart.service.GitRepoService;
import usi.si.seart.service.LabelService;
import usi.si.seart.service.LanguageService;
import usi.si.seart.service.TopicService;
import usi.si.seart.util.Dates;
import usi.si.seart.util.Optionals;
import usi.si.seart.util.Ranges;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Slf4j
@Service
@DependsOn("LanguageInitializationBean")
@ConditionalOnExpression(value = "${app.crawl.enabled:false} and not '${app.crawl.languages}'.isBlank()")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CrawlProjectsJob {

    Deque<Range<Date>> requestQueue = new ArrayDeque<>();

    GitRepoService gitRepoService;
    TopicService topicService;
    LabelService labelService;
    LanguageService languageService;

    ConversionService conversionService;

    GitHubAPIConnector gitHubApiConnector;

    @NonFinal
    @Value(value = "${app.crawl.scheduling}")
    Duration schedulingRate;

    Function<Date, String> dateStringMapper;

    @Scheduled(fixedDelayString = "${app.crawl.scheduling}")
    public void run() {
        log.info("Initializing language queue...");
        Collection<Language> languages = languageService.getTargetedLanguages();
        log.info("Language crawling order: {}", languages.stream().map(Language::getName).collect(Collectors.toList()));
        for (Language language : languages) {
            this.requestQueue.clear();
            Language.Progress progress = languageService.getProgress(language);
            Date startDate = progress.getCheckpoint();
            Date endDate = Date.from(Instant.now().minus(Duration.ofHours(2)));
            Range<Date> dateRange = Ranges.build(startDate, endDate);
            crawlRepositories(dateRange, language);
        }
        log.info("Next crawl scheduled for: {}", Date.from(Instant.now().plus(schedulingRate)));
    }

    private void crawlRepositories(Range<Date> range, Language language) {
        log.info("Starting crawling {} repositories updated through: {}", language.getName(), range);
        requestQueue.push(range);
        do {
            int limit = 5;
            int size = requestQueue.size();
            log.info("Next crawl intervals:");
            requestQueue.stream()
                    .limit(limit)
                    .map(item -> Ranges.toString(item, dateStringMapper))
                    .forEach(string -> log.info("\t[{}]", string));
            if (size > limit)
                log.info("\t{} omitted ...", size - limit);
            Range<Date> first = requestQueue.pop();
            retrieveRepositories(first, language);
        } while (!requestQueue.isEmpty());
        log.info("Finished crawling {} repositories updated through: {}", language.getName(), range);
    }

    private void retrieveRepositories(Range<Date> range, Language language) {
        String name = language.getName();
        int page = 1;
        try {
            JsonObject json = gitHubApiConnector.searchRepositories(name, range, page);
            int totalResults = json.get("total_count").getAsInt();
            int totalPages = (int) Math.ceil(totalResults / 100.0);
            if (totalResults == 0) return;
            log.info("Retrieved results: " + totalResults);
            if (totalResults > 1000) {
                try {
                    Pair<Range<Date>, Range<Date>> ranges = Ranges.split(range, Dates::median);
                    requestQueue.push(ranges.getRight());
                    requestQueue.push(ranges.getLeft());
                    return;
                } catch (UnsplittableRangeException ure) {
                    log.warn(
                            "Encountered range that could not be further split [{}]!",
                            Ranges.toString(range, dateStringMapper)
                    );
                    log.info("Proceeding with mining anyway to mitigate data loss...");
                }
            }
            JsonArray results = json.get("items").getAsJsonArray();
            saveRetrievedRepos(results, name, 1, totalResults);
            retrieveRemainingRepos(range, name, totalPages);
            Date checkpoint = range.upperEndpoint();
            log.info("{} repositories crawled up to: {}", name, checkpoint);
            languageService.updateProgress(language, checkpoint);
        } catch (NonTransientDataAccessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to retrieve repositories", ex);
        }
    }

    private void retrieveRemainingRepos(Range<Date> range, String language, int pages) {
        if (pages > 1) {
            int page = 2;
            while (page <= pages) {
                try {
                    JsonObject json = gitHubApiConnector.searchRepositories(language, range, page);
                    int totalResults = json.get("total_count").getAsInt();
                    JsonArray results = json.get("items").getAsJsonArray();
                    saveRetrievedRepos(results, language, (page - 1) * 100 + 1, totalResults);
                    page++;
                } catch (NonTransientDataAccessException ex) {
                    throw ex;
                } catch (Exception ex) {
                    log.error("Failed to retrieve the remaining repositories", ex);
                }
            }
        }
    }

    private void saveRetrievedRepos(JsonArray results, String language, int lowerIndex, int total) {
        int upperIndex = lowerIndex + results.size() - 1;
        log.info(
                "Crawling {} {} repositories ({} - {} | total: {})",
                results.size(), language, lowerIndex, upperIndex, total
        );

        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            saveRetrievedRepo(result, language, lowerIndex, total);
            lowerIndex++;
        }
    }

    private void saveRetrievedRepo(JsonObject result, String language, int lowerIndex, int total) {
        String name = result.getAsJsonPrimitive("full_name").getAsString();
        Optional<GitRepo> optional = Optionals.ofThrowable(() -> gitRepoService.getByName(name));
        GitRepo gitRepo = optional.orElseGet(() -> GitRepo.builder().name(name).build());
        String action = (gitRepo.getId() != null)
                ? "Updating:  "
                : "Saving:    ";
        log.info("{}{} [{}/{}]", action, name, lowerIndex, total);

        Date createdAt = Dates.fromGitDateString(result.getAsJsonPrimitive("created_at").getAsString());
        Date updatedAt = Dates.fromGitDateString(result.getAsJsonPrimitive("updated_at").getAsString());
        Date pushedAt = Dates.fromGitDateString(result.getAsJsonPrimitive("pushed_at").getAsString());

        if (shouldSkip(gitRepo, updatedAt, pushedAt)) {
            log.debug("\tSKIPPED: We already have the latest info!");
            log.trace("\t\tUpdated: {}", updatedAt);
            log.trace("\t\tPushed:  {}", pushedAt);
            return;
        }

        try {
            JsonObject json = gitHubApiConnector.fetchRepoInfo(name);

            String defaultBranch = json.get("default_branch").getAsString();
            gitRepo.setDefaultBranch(defaultBranch);

            String license = (!json.get("license").isJsonNull())
                    ? json.getAsJsonObject("license")
                    .getAsJsonPrimitive("name")
                    .getAsString()
                    .replace("\"", "")
                    : null;
            gitRepo.setLicense(license);

            String homepage = (!json.get("homepage").isJsonNull())
                    ? json.getAsJsonPrimitive("homepage").getAsString()
                    : null;
            gitRepo.setHomepage(Strings.emptyToNull(homepage));

            Long stargazers = json.getAsJsonPrimitive("stargazers_count").getAsLong();
            gitRepo.setStargazers(stargazers);
            Long forks = json.getAsJsonPrimitive("forks_count").getAsLong();
            gitRepo.setForks(forks);
            Long watchers = json.getAsJsonPrimitive("subscribers_count").getAsLong();
            gitRepo.setWatchers(watchers);
            Long size = json.getAsJsonPrimitive("size").getAsLong();
            gitRepo.setSize(size);

            gitRepo.setCreatedAt(createdAt);
            gitRepo.setPushedAt(pushedAt);
            gitRepo.setUpdatedAt(updatedAt);

            boolean hasWiki = json.getAsJsonPrimitive("has_wiki").getAsBoolean();
            gitRepo.setHasWiki(hasWiki);
            Boolean isFork = json.getAsJsonPrimitive("fork").getAsBoolean();
            gitRepo.setIsFork(isFork);
            Boolean isArchived = json.getAsJsonPrimitive("archived").getAsBoolean();
            gitRepo.setIsArchived(isArchived);

            Long commits = gitHubApiConnector.fetchNumberOfCommits(name);
            Long branches = gitHubApiConnector.fetchNumberOfBranches(name);
            Long releases = gitHubApiConnector.fetchNumberOfReleases(name);
            Long contributors = gitHubApiConnector.fetchNumberOfContributors(name);
            gitRepo.setCommits(commits);
            gitRepo.setBranches(branches);
            gitRepo.setReleases(releases);
            gitRepo.setContributors(contributors);

            Long totalPullRequests = gitHubApiConnector.fetchNumberOfAllPulls(name);
            Long openPullRequests = gitHubApiConnector.fetchNumberOfOpenPulls(name);
            gitRepo.setTotalPullRequests(totalPullRequests);
            gitRepo.setOpenPullRequests(openPullRequests);

            boolean hasIssues = json.getAsJsonPrimitive("has_issues").getAsBoolean();
            if (hasIssues) {
                Long totalIssues = gitHubApiConnector.fetchNumberOfAllIssuesAndPulls(name) - totalPullRequests;
                Long openIssues = gitHubApiConnector.fetchNumberOfOpenIssuesAndPulls(name) - openPullRequests;
                gitRepo.setTotalIssues(totalIssues);
                gitRepo.setOpenIssues(openIssues);
            } else {
                gitRepo.setTotalIssues(0L);
                gitRepo.setOpenIssues(0L);
            }

            GitCommit gitCommit = gitHubApiConnector.fetchLastCommitInfo(name);
            Date lastCommit = gitCommit.getDate();
            String lastCommitSHA = gitCommit.getSha();
            gitRepo.setLastCommit(lastCommit);
            gitRepo.setLastCommitSHA(lastCommitSHA);

            Language mainLanguage = languageService.getOrCreate(language);
            gitRepo.setMainLanguage(mainLanguage);

            Set<Label> labels = retrieveRepoLabels(gitRepo);
            if (!labels.isEmpty())
                log.debug("\tAdding: {} labels.", labels.size());
            gitRepo.setLabels(labels);

            Set<GitRepoLanguage> languages = retrieveRepoLanguages(gitRepo);
            if (!languages.isEmpty())
                log.debug("\tAdding: {} languages.", languages.size());
            gitRepo.setLanguages(languages);

            Set<Topic> topics = retrieveTopics(gitRepo);
            if (!topics.isEmpty())
                log.debug("\tAdding: {} topics.", topics.size());
            gitRepo.setTopics(topics);

            gitRepoService.updateRepo(gitRepo);
        } catch (NonTransientDataAccessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to save repository", ex);
        }
    }

    /*
     * Optimization for cases when crawler restarts
     * mid-interval and encounters unchanged information.
     */
    private boolean shouldSkip(GitRepo gitRepo, Date apiUpdatedAt, Date apiPushedAt) {
        Date dbUpdatedAt = gitRepo.getUpdatedAt();
        Date dbPushedAt = gitRepo.getPushedAt();
        boolean dbHasData = dbUpdatedAt != null && dbPushedAt != null;
        return dbHasData
                && dbUpdatedAt.compareTo(apiUpdatedAt) == 0
                && dbPushedAt.compareTo(apiPushedAt) == 0;
    }

    private Set<Label> retrieveRepoLabels(GitRepo repo) {
        try {
            JsonArray array = gitHubApiConnector.fetchRepoLabels(repo.getName());
            return StreamSupport.stream(array.spliterator(), true)
                    .map(element -> {
                        JsonObject object = element.getAsJsonObject();
                        String name = object.get("name").getAsString();
                        return labelService.getOrCreate(name.toLowerCase());
                    })
                    .collect(Collectors.toSet());
        } catch (NonTransientDataAccessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MetadataCrawlingException("Failed to retrieve repository labels", ex);
        }
    }

    private Set<GitRepoLanguage> retrieveRepoLanguages(GitRepo repo) {
        try {
            JsonObject object = gitHubApiConnector.fetchRepoLanguages(repo.getName());
            return object.entrySet().stream()
                    .map(entry -> {
                        Language language = languageService.getOrCreate(entry.getKey());
                        GitRepoLanguage.Key key = new GitRepoLanguage.Key(repo.getId(), language.getId());
                        return GitRepoLanguage.builder()
                                .key(key)
                                .repo(repo)
                                .language(language)
                                .sizeOfCode(entry.getValue().getAsLong())
                                .build();
                    })
                    .collect(Collectors.toSet());
        } catch (NonTransientDataAccessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MetadataCrawlingException("Failed to retrieve repository languages", ex);
        }
    }

    private Set<Topic> retrieveTopics(GitRepo repo) {
        try {
            JsonArray array = gitHubApiConnector.fetchRepoTopics(repo.getName());
            return StreamSupport.stream(array.spliterator(), true)
                    .map(entry -> topicService.getOrCreate(entry.getAsString()))
                    .collect(Collectors.toSet());
        } catch (NonTransientDataAccessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MetadataCrawlingException("Failed to retrieve repository topics", ex);
        }
    }
}
