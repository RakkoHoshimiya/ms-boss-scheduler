package com.gmsr.scheduler.repository;

import com.gmsr.scheduler.domain.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
