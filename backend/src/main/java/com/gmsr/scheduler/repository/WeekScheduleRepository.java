package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.WeekSchedule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeekScheduleRepository extends JpaRepository<WeekSchedule, String> {

    List<WeekSchedule> findAllByOrderByStartDateDesc();
}
