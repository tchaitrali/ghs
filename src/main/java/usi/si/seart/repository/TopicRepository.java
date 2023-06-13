package usi.si.seart.repository;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import usi.si.seart.model.Topic;

import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Optional;

public interface TopicRepository extends JpaRepository<Topic, Long> {

    Optional<Topic> findByName(@NotNull String name);

    @Query(
            "select t from Topic t " +
            "join t.repos " +
            "group by t.id " +
            "order by COUNT(*) desc"
    )
    @Cacheable(value = "topics")
    List<Topic> findMostFrequent(Pageable pageable);
}
