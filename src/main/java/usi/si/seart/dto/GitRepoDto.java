package usi.si.seart.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.*;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class GitRepoDto {
    Long id;
    String name;
    Boolean isFork;
    Long commits;
    Long branches;
    String defaultBranch;
    Long releases;
    Long contributors;
    String license;
    Long watchers;
    Long stargazers;
    Long forks;
    Long size;
    Date createdAt;
    Date pushedAt;
    Date updatedAt;
    String homepage;
    String mainLanguage;
    Long totalIssues;
    Long openIssues;
    Long totalPullRequests;
    Long openPullRequests;
    Long totalLines;
    Long totalCodeLines;
    Long totalCommentLines;
    @JacksonXmlElementWrapper(localName = "metrics")
    @JacksonXmlProperty(localName = "metric")
    @Builder.Default
    List<GitRepoMetricDTO> metrics = new ArrayList<>();
    Date lastCommit;
    String lastCommitSHA;
    Boolean hasWiki;
    Boolean isArchived;
    @JacksonXmlProperty(localName = "languages", isAttribute = true)
    @Builder.Default
    Map<String, Long> languages = new LinkedHashMap<>();
    @JacksonXmlElementWrapper(localName = "labels")
    @JacksonXmlProperty(localName = "label")
    @Builder.Default
    Set<String> labels = new TreeSet<>();
}
