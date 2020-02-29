package com.dabico.githubseapp.util;

import lombok.*;
import lombok.experimental.FieldDefaults;
import org.javatuples.Pair;

import java.text.SimpleDateFormat;
import java.util.Date;
import static com.dabico.githubseapp.util.DateUtils.*;

@Builder
@Getter
@Setter
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class DateInterval {

    Date start;
    Date end;

    public DateInterval(Date start, Date end){
        this.start = setInitDay(start);
        this.end   = setInitDay(end);
    }

    public Pair<DateInterval,DateInterval> splitInterval(){
        Date median = setInitDay(new Date((start.getTime() + end.getTime())/2));
        DateInterval firstInterval  = new DateInterval(start,median);
        DateInterval secondInterval = new DateInterval(median,end);
        return new Pair<>(firstInterval,secondInterval);
    }

    public String getSearchURL(){
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("YYYY-MM-DD");
        return "+created:" + simpleDateFormat.format(this.start) + ".."  + simpleDateFormat.format(this.end);
    }
}
