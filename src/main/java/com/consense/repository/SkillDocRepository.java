package com.consense.repository;

import com.consense.domain.SkillDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillDocRepository extends JpaRepository<SkillDoc, String> {

    List<SkillDoc> findAllByOrderBySortOrderAsc();
}
