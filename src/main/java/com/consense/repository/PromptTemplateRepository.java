package com.consense.repository;

import com.consense.domain.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, String> {

    List<PromptTemplate> findAllByOrderBySortOrderAsc();
}
