package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.web.dto.DraftingDtos.*;
import javax.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real updateVariable with isolated repository mocks; no model or existing project. */
class DraftAdoptionSnapshotTest {
    private static final String PROJECT="adoption-snapshot-test",KEY="designResponsibilities";
    private static final String FULL="[{\"component\":\"piling\",\"design\":true,\"execution\":true,\"scope\":\"All project piling\"},{\"component\":\"footings\",\"design\":true,\"execution\":true,\"scope\":\"All project footings\"}]";
    private static final String PARTIAL="[{\"component\":\"piling\",\"design\":true,\"execution\":true}]";
    private DraftingService service;private DraftVariable variable;private SourceDocument source;
    private CandidateVO full,partial;

    @BeforeEach void isolatedCurrentView() {
        ProjectService projects=mock(ProjectService.class);DraftVariableRepository variables=mock(DraftVariableRepository.class);
        SourceDocumentRepository sources=mock(SourceDocumentRepository.class);EntityManager em=mock(EntityManager.class);
        Map<String,DraftVariable> memory=new LinkedHashMap<>();
        when(variables.findByProjectIdAndVarKey(anyString(),anyString())).thenAnswer(inv->Optional.ofNullable(memory.get(inv.getArgument(1))));
        when(variables.findByProjectIdOrderBySortOrderAsc(anyString())).thenAnswer(inv->new ArrayList<>(memory.values()));
        when(variables.save(any(DraftVariable.class))).thenAnswer(inv->{DraftVariable value=inv.getArgument(0);memory.put(value.getVarKey(),value);return value;});
        variable=new DraftVariable();variable.setProjectId(PROJECT);variable.setVarKey(KEY);variable.setScope("INPUT");variable.setValueText(FULL);memory.put(KEY,variable);
        source=new SourceDocument();source.setId(7L);source.setProjectId(PROJECT);source.setCategory(SourceDocument.CATEGORY_PROJECT_INPUT);source.setParseStatus("PARSED");source.setTextContent("The complete responsibility schedule and its earlier foundation facts.");
        when(sources.findById(7L)).thenReturn(Optional.of(source));
        full=new CandidateVO(FULL,7L,"source.docx",DraftAdoption.sourceHash(source),"Complete schedule source.","Complete schedule",.99);
        partial=new CandidateVO(PARTIAL,7L,"source.docx",DraftAdoption.sourceHash(source),"Earlier partial fact.","Earlier foundation fact",.99);
        variable.setCandidatesJson(JsonUtils.write(Arrays.asList(full,partial)));variable.setSourceRef(full.getSourceQuote());
        service=new DraftingService(projects,null,null,null,sources,variables,null,null,null,null,null,null,em,null,null,null);
        service.listVariables(PROJECT);
    }

    @Test void selectedDisplayedCandidateIsAdoptedWhenItsIdentityStillMatches() {
        VariableVO result=service.updateVariable(PROJECT,KEY,candidate(0));
        assertEquals(FULL,result.getValue());assertTrue(result.isConfirmed());assertFalse(result.isManuallyEdited());
        assertEquals(full.getSourceQuote(),result.getSource());assertEquals(1,DraftAdoption.adoptedSources(variable).size());
    }

    @Test void staleCandidatePositionCannotAdoptTheReplacementDespiteAnUnchangedSourceHash() {
        VariablePatch displayed=candidate(0);variable.setCandidatesJson(JsonUtils.write(Arrays.asList(partial,full)));
        rejectWithoutAdoption(displayed);assertEquals(FULL,variable.getValueText());
    }

    @Test void missingCandidateOrSuggestionSnapshotIsAControlledRefreshError() {
        VariablePatch patch=new VariablePatch();patch.setCandidateIndex(0);rejectWithoutAdoption(patch);
        patch=new VariablePatch();patch.setConfirmed(true);rejectWithoutAdoption(patch);
        patch=new VariablePatch();patch.setReviewed(true);rejectWithoutAdoption(patch);
    }

    @Test void staleSuggestionAndReviewedButtonsCannotAdoptAValueTheyDidNotDisplay() {
        for(boolean reviewed:Arrays.asList(false,true)) {
            variable.setValueText(FULL);VariablePatch displayed=confirmation(reviewed);
            variable.setValueText(PARTIAL);rejectWithoutAdoption(displayed);assertEquals(PARTIAL,variable.getValueText());
        }
    }

    @Test void unchangedValueDoesNotHideChangedEvidenceOrANewSourceReviewRequirement() {
        VariablePatch displayed=confirmation(false);
        CandidateVO revised=copy(full);revised.setSourceQuote("Revised complete schedule source.");
        variable.setCandidatesJson(JsonUtils.write(Arrays.asList(revised,partial)));rejectWithoutAdoption(displayed);
        variable.setCandidatesJson(JsonUtils.write(Arrays.asList(full,partial)));displayed=confirmation(false);
        variable.setReviewRequired(true);rejectWithoutAdoption(displayed);assertTrue(variable.getReviewRequired());
        VariableVO reviewed=service.updateVariable(PROJECT,KEY,confirmation(true));
        assertEquals(FULL,reviewed.getValue());assertTrue(reviewed.isConfirmed());assertFalse(reviewed.isReviewRequired());
    }

    @Test void currentSuggestionCanBeAdoptedAndCurrentSourceHashGuardStillApplies() {
        VariableVO result=service.updateVariable(PROJECT,KEY,confirmation(false));assertTrue(result.isConfirmed());assertEquals(FULL,result.getValue());
        variable.setConfirmed(false);VariablePatch displayed=candidate(0);source.setTextContent("Changed original correspondence.");rejectWithoutAdoption(displayed);
    }

    @Test void malformedSnapshotListAndChangedIdentityCannotTurnIntoServerErrors() {
        VariablePatch patch=confirmation(false);patch.getSuggestionSnapshot().setCandidates(Arrays.asList((CandidateVO)null));rejectWithoutAdoption(patch);
        patch=candidate(0);patch.getCandidateSnapshot().setSourceHash("another hash");rejectWithoutAdoption(patch);
        patch=candidate(0);patch.getCandidateSnapshot().setSourceDocumentId(999L);rejectWithoutAdoption(patch);
    }

    @Test void anExplicitManualValueRemainsAnIntentionalEditWithoutASnapshot() {
        VariablePatch patch=new VariablePatch();patch.setValue(PARTIAL);
        VariableVO result=service.updateVariable(PROJECT,KEY,patch);
        assertEquals(PARTIAL,result.getValue());assertTrue(result.isConfirmed());assertTrue(result.isManuallyEdited());
    }

    private VariablePatch candidate(int index) {
        VariablePatch patch=new VariablePatch();patch.setCandidateIndex(index);patch.setCandidateSnapshot(copy(DraftAdoption.candidates(variable).get(index)));return patch;
    }
    private VariablePatch confirmation(boolean reviewed) {
        VariableVO shown=service.listVariables(PROJECT).stream().filter(v->KEY.equals(v.getKey())).findFirst().get();
        VariablePatch patch=new VariablePatch();patch.setConfirmed(true);if(reviewed)patch.setReviewed(true);
        patch.setSuggestionSnapshot(new SuggestionSnapshot(shown.getValue(),shown.getSource(),shown.getCandidates(),shown.isReviewRequired()));return patch;
    }
    private static CandidateVO copy(CandidateVO candidate) {return JsonUtils.read(JsonUtils.write(candidate),CandidateVO.class);}
    private void rejectWithoutAdoption(VariablePatch patch) {
        String before=JsonUtils.write(Arrays.asList(variable.getValueText(),variable.getConfirmed(),variable.getManuallyEdited(),variable.getReviewRequired(),variable.getSourceRef(),variable.getAdoptedSourcesJson(),variable.getNoteText()));
        BizException rejection=assertThrows(BizException.class,()->service.updateVariable(PROJECT,KEY,patch));assertEquals(4007,rejection.getCode());
        assertEquals(before,JsonUtils.write(Arrays.asList(variable.getValueText(),variable.getConfirmed(),variable.getManuallyEdited(),variable.getReviewRequired(),variable.getSourceRef(),variable.getAdoptedSourcesJson(),variable.getNoteText())));
    }
}
