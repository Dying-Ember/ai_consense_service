package com.consense.service.drafting;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source-bound citation repair. Never supplies a missing model item or joins separate passages. */
final class DraftCandidateEvidenceRecovery {
    private static final Pattern CANDIDATE_MARKER=Pattern.compile("(?m)(?:^[\\t ]*|(?<=[;:])[\\t ]*)(\\d{1,4})(?:[.)][\\t ]*|(?=[\\p{L}]))");
    private static final Pattern UNASSERTED=Pattern.compile("(?i)\\b(?:pending|unknown|unconfirmed|undetermined|not supplied|not provided|to be confirmed|please confirm|please provide|whether|proposed|unselected|alternatives?|options?|if|unless|subject to|not adopted|not selected|not confirmed|not applicable|not required|excluded|do not (?:use|adopt|apply)|does not apply)\\b|\\?");
    private static final Pattern NEGATED_ORDERED_SCOPE=Pattern.compile("(?i)\\b(?:no|none of|neither)\\b[^.;\\n]*\\b(?:required|adopted|included|specified|selected|applicable|apply|applies)\\b|\\bnot\\s+(?:included|specified)\\b");
    private DraftCandidateEvidenceRecovery() { }

    static final class Result {
        final Object value;
        final String quote;
        final List<String> codes;
        Result(Object value,String quote,String... codes) {this.value=value;this.quote=quote;this.codes=Arrays.asList(codes);}
    }

    static Result assess(DraftBlueprint.InputSpec spec,Object value,String quote,String supplied,String original,boolean canRecover) {
        if(original==null)original=supplied;
        if("text".equals(spec.kind)&&value instanceof String)
            return ordered(spec,(String)value,quote,supplied,original,canRecover);
        if("billNos".equals(spec.key)&&value instanceof List&&!((List<?>)value).isEmpty())
            return bills(value,quote,supplied,original,canRecover);
        if("designResponsibilities".equals(spec.key)&&value instanceof List&&!((List<?>)value).isEmpty()) {
            String scope=DraftDesignEvidence.quotedTableScope(quote,original);
            if(scope!=null&&!scope.equals(quote)) {
                if(!DraftEvidenceQuotes.present(supplied,scope))return new Result(value,quote,"context_incomplete_source");
                if(!DraftDesignEvidence.supported(value,scope))return new Result(value,quote,"quote_value_mismatch");
                if(canRecover)return new Result(value,scope,"evidence_quote_reanchored");
            }
        }
        if("sections".equals(spec.key)&&value instanceof List&&!((List<?>)value).isEmpty()) {
            String scope=DraftSectionEvidence.quotedTableScope(quote,original);
            if(scope!=null&&!scope.equals(quote)) {
                if(!DraftEvidenceQuotes.present(supplied,scope))return new Result(value,quote,"context_incomplete_source");
                if(!DraftSectionEvidence.supported(value,scope))return new Result(value,quote,"quote_value_mismatch");
                if(canRecover)return new Result(value,scope,"evidence_quote_reanchored");
            }
        }
        return new Result(value,quote);
    }

    static boolean diagnostic(String code) {
        return "evidence_quote_reanchored".equals(code)||"source_value_typography_restored".equals(code);
    }

    private static Result ordered(DraftBlueprint.InputSpec spec,String value,String quote,String supplied,String original,boolean canRecover) {
        List<Item> candidate=items(value);
        List<DraftOrderedSourceLists.Unit> matches=new ArrayList<>();
        for(DraftOrderedSourceLists.Unit unit:DraftOrderedSourceLists.units(original)) {
            if(!fieldHeading(spec,original.substring(unit.start,unit.listStart)))continue;
            String scope=original.substring(unit.start,unit.end);
            boolean overlap=quote!=null&&!quote.trim().isEmpty()&&DraftEvidenceQuotes.present(scope,quote);
            for(Item item:candidate)for(DraftOrderedSourceLists.Entry entry:unit.entries)
                if(item.number==entry.number&&itemIdentity(item.text).equals(itemIdentity(entry.text)))overlap=true;
            if(overlap)matches.add(unit);
        }
        if(matches.isEmpty())return new Result(value,quote);
        if(matches.size()!=1)return new Result(value,quote,"source_list_ambiguous");
        DraftOrderedSourceLists.Unit unit=matches.get(0);
        if(!unit.validSequence)return new Result(value,quote,"source_list_invalid_sequence");
        if(candidate.size()!=unit.entries.size())return new Result(value,quote,"source_list_incomplete");
        String candidateLead=value.substring(0,candidate.get(0).start).trim();
        String sourceLead=original.substring(unit.start,unit.listStart);
        if(!candidateLead.isEmpty()&&(unassertedOrderedScope(candidateLead)||!DraftEvidenceQuotes.present(sourceLead,candidateLead)))
            return new Result(value,quote,"source_list_value_mismatch");
        for(int i=0;i<candidate.size();i++) {
            Item item=candidate.get(i);DraftOrderedSourceLists.Entry entry=unit.entries.get(i);
            if(item.number!=entry.number)return new Result(value,quote,"source_list_incomplete");
            if(!itemIdentity(item.text).equals(itemIdentity(entry.text)))return new Result(value,quote,"source_list_value_mismatch");
        }
        if(unit.foreignProject||unassertedOrderedScope(unit.governingText))return new Result(value,quote,"source_list_unresolved");
        String sourceValue=original.substring(unit.listStart,unit.listEnd).trim();
        String scope=original.substring(unit.start,unit.end);
        if(!DraftEvidenceQuotes.present(supplied,scope))return new Result(value,quote,"context_incomplete_source");
        boolean exactValue=DraftEvidenceQuotes.textIdentity(value).equals(DraftEvidenceQuotes.textIdentity(sourceValue));
        if(exactValue&&DraftEvidenceQuotes.present(supplied,quote)&&DraftCandidateGrounding.directLiteralSupported(spec,value,quote))return new Result(value,quote);
        if(!canRecover||!orderedQuoteAnchor(quote,scope,unit))return new Result(value,quote);
        // Only the list marker may differ. All item bodies and their ordered identifiers already matched.
        if(!exactValue)return new Result(sourceValue,scope,"source_value_typography_restored","evidence_quote_reanchored");
        return new Result(value,scope,"evidence_quote_reanchored");
    }

    private static boolean unassertedOrderedScope(String governing) {
        return UNASSERTED.matcher(governing).find()||NEGATED_ORDERED_SCOPE.matcher(governing).find();
    }


    private static boolean orderedQuoteAnchor(String quote,String scope,DraftOrderedSourceLists.Unit unit) {
        if(quote==null||quote.trim().isEmpty()||UNASSERTED.matcher(quote).find())return false;
        if(DraftEvidenceQuotes.present(scope,quote))return true;
        List<Item> quoted=items(quote);if(quoted.size()!=unit.entries.size())return false;
        for(int i=0;i<quoted.size();i++) {
            Item item=quoted.get(i);DraftOrderedSourceLists.Entry entry=unit.entries.get(i);
            if(item.number!=entry.number||!itemIdentity(item.text).equals(itemIdentity(entry.text)))return false;
        }
        // Reject invented prefixes/suffixes around a matching enumeration; a source label is optional.
        String lead=quote.substring(0,quoted.get(0).start).trim();
        return lead.isEmpty()||DraftEvidenceQuotes.present(scope,lead);
    }

    private static boolean fieldHeading(DraftBlueprint.InputSpec spec,String prefix) {
        Set<String> terms=new HashSet<>();
        for(String word:spec.labelEn.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            if(word.length()>3&&!Arrays.asList("other","specified","includes","included","project","contract","this","with","under").contains(word))terms.add(word);
        int hits=0;
        for(String word:terms)if(Pattern.compile("(?i)\\b"+Pattern.quote(word)+"\\b").matcher(prefix).find())hits++;
        return hits>=2;
    }

    private static final class Item {
        final int number,start;final String text;
        Item(int number,int start,String text){this.number=number;this.start=start;this.text=text;}
    }

    private static List<Item> items(String value) {
        List<int[]> markers=new ArrayList<>();Matcher matcher=CANDIDATE_MARKER.matcher(value);
        while(matcher.find())markers.add(new int[]{Integer.parseInt(matcher.group(1)),matcher.start(1),matcher.end(),matcher.start()});
        List<Item> result=new ArrayList<>();
        for(int i=0;i<markers.size();i++) {
            int[] marker=markers.get(i);int end=i+1<markers.size()?markers.get(i+1)[3]:value.length();
            result.add(new Item(marker[0],marker[1],value.substring(marker[2],end).trim()));
        }
        return result;
    }

    private static String itemIdentity(String text) {
        String identity=DraftEvidenceQuotes.textIdentity(text);
        // A semicolon immediately before the next numbered entry is an enumeration separator only.
        return identity.endsWith(";")?identity.substring(0,identity.length()-1).trim():identity;
    }

    private static Result bills(Object value,String quote,String supplied,String original,boolean canRecover) {
        List<DraftBillSourceTables.Table> matches=new ArrayList<>();
        int quotedLogicalUnits=0;
        for(DraftBillSourceTables.Table table:DraftBillSourceTables.tables(original)) {
            String scope=original.substring(table.start,table.end);
            // Adoption cannot disambiguate a repeated literal introduction or table quotation.
            // Resolve its logical source occurrence before filtering which roles can support values.
            if(quote!=null&&DraftBillSourceTables.identityRowsSupported(value,table)&&
                    (DraftEvidenceQuotes.present(scope,quote)||DraftEvidenceQuotes.present(quote,scope)))quotedLogicalUnits++;
            if(DraftCandidateGrounding.billIdentitiesSupported(value,scope))matches.add(table);
        }
        if(quotedLogicalUnits>1)return new Result(value,quote,"source_list_ambiguous");
        if(matches.size()>1)return new Result(value,quote,"source_list_ambiguous");
        if(matches.isEmpty())return new Result(value,quote);
        DraftBillSourceTables.Table table=matches.get(0);String scope=original.substring(table.start,table.end);
        boolean complete=DraftBillSourceTables.complete(value,table);
        boolean declaredComplete=table.declaredComplete;
        if(declaredComplete&&!complete)return new Result(value,quote,"source_list_incomplete");
        if(UNASSERTED.matcher(table.prefix).find())return new Result(value,quote,"source_list_unresolved");
        if(DraftEvidenceQuotes.present(supplied,quote)&&DraftCandidateGrounding.billIdentitiesSupported(value,quote))return new Result(value,quote);
        if(!canRecover||!complete||quote==null||UNASSERTED.matcher(quote).find()||
                !DraftEvidenceQuotes.present(scope,quote)||!DraftEvidenceQuotes.present(supplied,scope))return new Result(value,quote);
        return new Result(value,scope,"evidence_quote_reanchored");
    }
}
