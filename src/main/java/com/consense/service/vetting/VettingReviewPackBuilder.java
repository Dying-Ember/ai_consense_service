package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import lombok.Data;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Source-linked material: ranked comparison experiments and explicit project-reference comparisons. */
public final class VettingReviewPackBuilder {
    public static final String STRATEGY = "source-linked-packs-prototype-v1";
    public static final int MAX_PACKS = 2;
    private static final Pattern ITEM = Pattern.compile("(?m)^\\s*(?:\\(([a-z0-9]+)\\)|([a-z0-9]+)[.)])\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTRO = Pattern.compile("(?i)\\b(?:following|as follows|except|unless|provided that|subject to|according to)\\b");
    private static final Pattern EDITION = Pattern.compile("(?i)(?:edition\\s*(\\d{4})|(\\d{4})\\s+edition)");
    private static final Pattern OBLIGATION = Pattern.compile("(?i)\\b(?:shall|must)\\b|\\b(?:means|mean)\\s+(?:the|an?|that)\\b");
    private final Map<String, Chunk> byId = new LinkedHashMap<>();
    private final Map<String, List<Chunk>> scopes = new LinkedHashMap<>(), clauses = new LinkedHashMap<>();
    private final Map<String, Integer> sourceOrder = new HashMap<>();
    private final Map<String, String> sourceEditions = new HashMap<>();
    private final Pattern reference;

    @Data public static class LinkTrace {
        private String originId, targetId, relation, status, referenceId;
        private boolean required;
        private int incrementalChars;
    }
    @Data public static class Pack {
        private int index, contentChars;
        private List<String> referenceIds = new ArrayList<>();
        private List<String> coreIds = new ArrayList<>(), unresolvedComparisonIds = new ArrayList<>(), unresolvedReferences = new ArrayList<>();
        private List<Chunk> chunks = new ArrayList<>();
        private List<LinkTrace> links = new ArrayList<>();
        private Map<String, List<String>> omittedScopeChunks = new LinkedHashMap<>();
        private List<String> omittedSupportIds = new ArrayList<>();
        private String scopeCoverage = "local_window", qualifiersComplete = "unknown";
    }
    @Data public static class Result {
        private List<Pack> packs = new ArrayList<>();
        private List<String> omittedRankedIds = new ArrayList<>(), notCoreRankedIds = new ArrayList<>(), excludedRankedIds = new ArrayList<>();
        private int rankedUniqueCount, maxPacks, perPackLimit;
        private int totalFactComparisons;
        private Map<String,String> unresolvedFactReferences = new LinkedHashMap<>();
        private List<String> omittedFactReferences = new ArrayList<>();
        private String strategy = STRATEGY;
    }

    /**
     * Project messages nominate their explicitly named tender provisions independently of topic rank.
     * Entire original chunks are retained; messages are context, never competing obligations.
     */
    public Result buildFactComparisons(int perPackLimit, int maxPacks) {
        Result result = new Result(); result.setStrategy("source-fact-comparisons-v1");
        result.setPerPackLimit(Math.max(0, perPackLimit)); result.setMaxPacks(Math.max(0, maxPacks));
        Map<String,LinkedHashMap<String,Chunk>> facts = new LinkedHashMap<>();
        Map<String,Set<String>> requested = new LinkedHashMap<>();
        for (Chunk fact : byId.values()) if ("project_fact".equals(fact.getRole()) && usable(fact)) {
            for (String ref : factReferences(fact)) {
                String parent = ref.replaceAll("\\([A-Z0-9]+\\)", "");
                facts.computeIfAbsent(parent, key -> new LinkedHashMap<>()).putIfAbsent(fact.getId(), fact);
                requested.computeIfAbsent(parent, key -> new LinkedHashSet<>()).add(ref);
            }
        }
        result.setTotalFactComparisons(facts.size());
        for (Map.Entry<String,LinkedHashMap<String,Chunk>> entry : facts.entrySet()) {
            String parent = entry.getKey();
            List<Chunk> targets = ownedTargets(parent, "tender");
            if (targets.isEmpty()) { result.getUnresolvedFactReferences().put(parent, "tender_target_not_located"); continue; }
            if (targets.stream().map(VettingReviewPackBuilder::targetIdentity).distinct().count() != 1) {
                result.getUnresolvedFactReferences().put(parent, "ambiguous_tender_source_or_scope"); continue;
            }
            if (result.getPacks().size() >= result.getMaxPacks()) { result.getOmittedFactReferences().add(parent + " (comparison limit)"); continue; }
            List<Chunk> required = new ArrayList<>(targets); required.addAll(entry.getValue().values());
            if (cost(required, Collections.emptyMap()) > result.getPerPackLimit()) {
                result.getOmittedFactReferences().add(parent + " (complete tender and project context exceeds budget)"); continue;
            }
            Pack pack = new Pack(); pack.setIndex(result.getPacks().size() + 1);
            pack.setReferenceIds(new ArrayList<>(requested.get(parent)));
            pack.setCoreIds(targets.stream().map(Chunk::getId).collect(Collectors.toList()));
            LinkedHashMap<String,Chunk> selected = new LinkedHashMap<>();
            Chunk origin = entry.getValue().values().iterator().next();
            for (Chunk target : targets) add(pack, selected, origin, target, "project_reference_target", true, result.getPerPackLimit());
            for (Chunk fact : entry.getValue().values()) add(pack, selected, targets.get(0), fact, "project_reference_context", true, result.getPerPackLimit());
            // A parent scope is useful context but is not proof that every requested subparagraph exists.
            for (String ref : requested.get(parent)) if (!ref.equals(parent) && targets.stream().noneMatch(c -> subparagraphLocated(ref, c))) {
                pack.getUnresolvedReferences().add(ref + " (subparagraph_not_located_in_owned_scope)");
                targets.forEach(c -> markUnresolved(pack, c.getId()));
            }
            List<Chunk> standards = ownedTargets(parent, "standard");
            if (standards.stream().map(VettingReviewPackBuilder::targetIdentity).distinct().count() > 1) {
                pack.getUnresolvedReferences().add(parent + " (ambiguous_standard_source_or_scope)");
                targets.forEach(c -> markUnresolved(pack, c.getId()));
            } else if (cost(standards, selected) <= result.getPerPackLimit() - pack.getContentChars()) {
                for (Chunk standard : standards) add(pack, selected, targets.get(0), standard, "standard_corresponding_scope", true, result.getPerPackLimit());
            } else {
                for (Chunk standard : standards) {
                    LinkTrace trace = new LinkTrace(); trace.setOriginId(targets.get(0).getId()); trace.setTargetId(standard.getId());
                    trace.setRelation("standard_corresponding_scope"); trace.setRequired(true); trace.setStatus("dropped_budget");
                    trace.setIncrementalChars(nvl(standard.getContent()).length()); pack.getLinks().add(trace); pack.getOmittedSupportIds().add(standard.getId());
                }
                pack.getUnresolvedReferences().add(parent + " (complete standard scope exceeds budget)");
                targets.forEach(c -> markUnresolved(pack, c.getId()));
            }
            pack.setChunks(new ArrayList<>(selected.values()));
            pack.setScopeCoverage("original_tender_scope_and_project_context");
            result.getPacks().add(pack);
        }
        return result;
    }

    private List<Chunk> ownedTargets(String parent, String role) {
        return byId.values().stream().filter(c -> role.equals(c.getRole()) && usable(c) && ownedClause(c)
                && matches(parent, canonical(c.getClauseId())) && scope(c) != null).collect(Collectors.toList());
    }

    private Set<String> factReferences(Chunk fact) {
        Set<String> refs = new LinkedHashSet<>(); if (reference == null) return refs;
        Matcher matcher = reference.matcher(nvl(fact.getContent()));
        while (matcher.find()) {
            String number = canonical(matcher.group(3)), ref = canonical(matcher.group(1) + matcher.group(3));
            if (matcher.group(2) == null && !number.contains(".") && !number.contains("(")
                    && clauses.keySet().stream().noneMatch(clause -> matches(ref, clause))
                    && nvl(fact.getContent()).substring(matcher.end()).matches("(?is)^\\s*edition\\b.*")) continue;
            refs.add(ref);
            // Only an immediately following comma-separated subparagraph list inherits this owner/number.
            if (ref.contains("(")) {
                String parent = ref.substring(0, ref.indexOf('('));
                Matcher abbreviated = Pattern.compile("\\s*,\\s*(\\([A-Z0-9]+\\))", Pattern.CASE_INSENSITIVE).matcher(nvl(fact.getContent()));
                abbreviated.region(matcher.end(), nvl(fact.getContent()).length());
                while (abbreviated.lookingAt()) {
                    refs.add(parent + canonical(abbreviated.group(1)));
                    abbreviated.region(abbreviated.end(), nvl(fact.getContent()).length());
                }
            }
        }
        return refs;
    }
    private static final class Target {
        final List<Chunk> chunks; final String reason;
        Target(List<Chunk> chunks, String reason) { this.chunks=chunks; this.reason=reason; }
    }

    public VettingReviewPackBuilder(List<Chunk> corpus) {
        Set<String> owners=new LinkedHashSet<>();
        for(Chunk c:corpus) {
            if(c==null||c.getId()==null||byId.putIfAbsent(c.getId(),c)!=null) throw new IllegalArgumentException("Missing or duplicate corpus chunk ID");
            sourceOrder.put(c.getId(),sourceOrder.size());
            if(!nvl(c.getFileKey()).isEmpty()&&!"OTHER".equals(c.getFileKey()))owners.add(c.getFileKey());
        }
        String keys=owners.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        reference=keys.isEmpty()?null:Pattern.compile("(?i)\\b("+keys+")\\s*(?:(clauses?)\\s+)?((?:\\.\\s*[A-Z]+)?\\s*\\.?\\s*\\d+(?:\\s*\\.\\s*\\d+)*(?:\\([A-Z0-9]+\\))*(?:\\s*\\.\\s*[A-Z](?=\\s|[,;:]|$))?)");
        for(Chunk c:corpus) if(usable(c)) {
            String scope=scope(c);if(scope!=null)scopes.computeIfAbsent(scope,k->new ArrayList<>()).add(c);
            if(ownedClause(c)&&Arrays.asList("tender","standard").contains(c.getRole()))
                clauses.computeIfAbsent(canonical(c.getClauseId()),k->new ArrayList<>()).add(c);
        }
        // An edition is identified from the source title, not an incidental year cited in a clause.
        Map<String,List<Chunk>> sources=corpus.stream().collect(Collectors.groupingBy(VettingReviewPackBuilder::sourceIdentity,LinkedHashMap::new,Collectors.toList()));
        for(Map.Entry<String,List<Chunk>> source:sources.entrySet()) {
            Chunk first=source.getValue().get(0);Set<String> editions=editions(first.getFileName());
            if(editions.isEmpty()) {
                String title=nvl(first.getContent());editions=editions(title.substring(0,Math.min(500,title.length())));
            }
            if(editions.size()==1)sourceEditions.put(source.getKey(),editions.iterator().next());
        }
    }

    /** Every adopted core is an original ranked tender chunk; no entity fallback replaces its rank. */
    public Result build(List<Chunk> rankedTender, Map<String,List<Chunk>> rankedReferences, int perPackLimit, int maxPacks) {
        Result result=new Result();result.setPerPackLimit(Math.max(0,perPackLimit));result.setMaxPacks(Math.min(MAX_PACKS,Math.max(0,maxPacks)));
        List<Chunk> seeds=new ArrayList<>();Set<String> unique=new LinkedHashSet<>();
        for(Chunk supplied:rankedTender) {
            Chunk c=original(supplied);
            if(!unique.add(c.getId()))continue;
            if(!"tender".equals(c.getRole())||!usable(c))result.getExcludedRankedIds().add(c.getId());else seeds.add(c);
        }
        result.setRankedUniqueCount(seeds.size());
        Map<String,Integer> rank=new HashMap<>();for(int i=0;i<seeds.size();i++)rank.put(seeds.get(i).getId(),i);
        Map<String,List<Chunk>> supporting=new LinkedHashMap<>();
        for(Map.Entry<String,List<Chunk>> e:rankedReferences.entrySet()) {
            List<Chunk> values=new ArrayList<>();for(Chunk supplied:e.getValue()) {
                Chunk c=original(supplied);if(e.getKey().equals(c.getRole())&&usable(c))values.add(c);
            }supporting.put(e.getKey(),values);
        }
        int at=0;
        while(at<seeds.size()&&result.getPacks().size()<result.getMaxPacks()) {
            int remaining=seeds.size()-at, size=remaining==4&&result.getMaxPacks()-result.getPacks().size()>=2?2:Math.min(3,remaining);
            List<Chunk> cores=new ArrayList<>(seeds.subList(at,at+size));
            if(size==1&&at>0)cores.add(seeds.get(at-1));
            if(cores.size()<2)break;
            while(cores.size()>2&&cost(cores,Collections.emptyMap())>result.getPerPackLimit()) {cores.remove(cores.size()-1);size--;}
            if(cost(cores,Collections.emptyMap())>result.getPerPackLimit())break;
            Pack pack=new Pack();pack.setIndex(result.getPacks().size()+1);pack.setCoreIds(cores.stream().map(Chunk::getId).collect(Collectors.toList()));
            LinkedHashMap<String,Chunk> selected=new LinkedHashMap<>();for(Chunk core:cores)add(pack,selected,null,core,"ranked_core",true,result.getPerPackLimit());
            // A lower-ranked side that explicitly refers to an adopted core remains a ranked comparison,
            // rather than being replaced by an unrelated common-actor context window.
            for(Chunk peer:seeds)if(!selected.containsKey(peer.getId())) {
                Chunk linked=cores.stream().filter(core->references(peer).stream().anyMatch(ref->target(ref,peer).chunks.stream().anyMatch(t->sameScope(t,core)))).findFirst().orElse(null);
                if(linked!=null)add(pack,selected,linked,peer,"ranked_reverse_reference",true,result.getPerPackLimit());
            }
            // Structural continuation is source-linked, never a fixed +/- number of chunks.
            cores.stream().sorted(Comparator.comparingInt((Chunk c)->qualificationPriority(c)).reversed())
                    .forEach(core->continuations(pack,selected,core,result.getPerPackLimit()));
            // References asserted by the actual supplied wording precede unrelated support hits.
            List<Chunk> origins=new ArrayList<>(selected.values());
            for(Chunk origin:origins)for(String ref:references(origin)) {
                Target resolved=target(ref,origin);
                if(resolved.chunks.isEmpty()) {unresolved(pack,origin,ref,resolved.reason);continue;}
                if(resolved.chunks.stream().anyMatch(c->selected.containsKey(c.getId())))continue;
                Chunk focus=focus(resolved.chunks,origin,rank);
                if(add(pack,selected,origin,focus,"explicit_reference",true,result.getPerPackLimit()))continuations(pack,selected,focus,result.getPerPackLimit());
                else pack.getUnresolvedReferences().add(ref+" (budget)");
            }
            // Facts must name an adopted clause; manifest rows must name an actual adopted file.
            for(String role:Arrays.asList("project_fact","package_manifest","standard"))for(Chunk supportingChunk:supporting.getOrDefault(role,Collections.emptyList())) {
                Chunk linked=related(supportingChunk,selected.values());if(linked==null)continue;
                add(pack,selected,linked,supportingChunk,"project_fact".equals(role)?"fact_clause_reference":"package_manifest".equals(role)?"manifest_filename":"standard_clause_reference",false,result.getPerPackLimit());
            }
            // Bring the identified heading, then entire short scopes only when the remainder fits atomically.
            List<Chunk> adopted=new ArrayList<>(selected.values());Set<String> visited=new HashSet<>();
            for(Chunk core:adopted) {
                String scope=scope(core);if(scope==null||!visited.add(scope))continue;List<Chunk> same=scopes.get(scope);
                add(pack,selected,core,same.get(0),"scope_heading",true,result.getPerPackLimit());
                if(cost(same,selected)<=result.getPerPackLimit()-pack.getContentChars())for(Chunk c:same)add(pack,selected,core,c,"complete_short_scope",false,result.getPerPackLimit());
            }
            for(Chunk core:new ArrayList<>(selected.values())) {
                if(!Arrays.asList("tender","standard").contains(core.getRole()))continue;
                String scope=scope(core);
                if(scope==null) {markUnresolved(pack,core.getId());continue;}
                List<String> omitted=scopes.get(scope).stream().filter(c->!selected.containsKey(c.getId())).map(Chunk::getId).collect(Collectors.toList());
                if(!omitted.isEmpty()) {pack.getOmittedScopeChunks().put(core.getId(),omitted);markUnresolved(pack,core.getId());}
                for(String ref:references(core)) {
                    Target resolved=target(ref,core);
                    if(resolved.chunks.stream().noneMatch(c->selected.containsKey(c.getId())))
                        unresolved(pack,core,ref,resolved.chunks.isEmpty()?resolved.reason:"target_outside_pack");
                }
            }
            if(pack.getUnresolvedComparisonIds().isEmpty()&&pack.getUnresolvedReferences().isEmpty()) {
                // Parent applicability, amendments and unrecognised scope remain unknown.
                pack.setScopeCoverage("identified_scope_fragments_complete");
            }
            pack.setChunks(new ArrayList<>(selected.values()));result.getPacks().add(pack);at+=size;
        }
        Set<String> coreUnion=result.getPacks().stream().flatMap(p->p.getCoreIds().stream()).collect(Collectors.toSet());
        Set<String> submittedUnion=result.getPacks().stream().flatMap(p->p.getChunks().stream()).map(Chunk::getId).collect(Collectors.toSet());
        for(Chunk seed:seeds) {
            if(!coreUnion.contains(seed.getId()))result.getNotCoreRankedIds().add(seed.getId());
            if(!submittedUnion.contains(seed.getId()))result.getOmittedRankedIds().add(seed.getId());
        }
        return result;
    }

    private void continuations(Pack pack,LinkedHashMap<String,Chunk> selected,Chunk origin,int limit) {
        String scope=scope(origin);if(scope==null)return;List<Chunk> same=scopes.get(scope);
        int start=same.indexOf(origin),left=start,right=start;
        // Finish an opened qualifying list before preceding unrelated list material.
        while(right+1<same.size()&&connected(same.get(right),same.get(right+1))) {
            Chunk c=same.get(right+1);if(!add(pack,selected,origin,c,"scope_continuation",true,limit))break;right++;
        }
        while(left>0&&connected(same.get(left-1),same.get(left))) {
            Chunk c=same.get(left-1);if(!add(pack,selected,origin,c,"scope_continuation",true,limit))break;left--;
        }
    }
    private boolean connected(Chunk left,Chunk right) {
        // Source order must be immediate, same revision, and same identified heading.
        if(!Objects.equals(scope(left),scope(right))||!Objects.equals(left.getSourceHash(),right.getSourceHash())
                ||sourceOrder.get(right.getId())!=sourceOrder.get(left.getId())+1)return false;
        if(left.getParts()!=null&&right.getParts()!=null)for(Part a:left.getParts())for(Part b:right.getParts())
            if(a.getBlockId()!=null&&a.getBlockId().equals(b.getBlockId())&&b.getStartOffset()>a.getStartOffset()&&b.getStartOffset()<=a.getEndOffset())return true;
        List<String> last=items(left.getContent()),first=items(right.getContent());
        if(!last.isEmpty()&&!first.isEmpty()&&successor(last.get(last.size()-1),first.get(0)))return true;
        String text=nvl(left.getContent()).trim();
        return !first.isEmpty()&&isFirst(first.get(0))&&INTRO.matcher(text).find()&&text.endsWith(":");
    }
    private static List<String> items(String text) {
        List<String> out=new ArrayList<>();Matcher matcher=ITEM.matcher(nvl(text));while(matcher.find())out.add(nvl(matcher.group(1)).isEmpty()?matcher.group(2):matcher.group(1));return out;
    }
    private static boolean successor(String left,String right) {
        if(left.matches("\\d+")&&right.matches("\\d+"))try{return Long.parseLong(right)==Long.parseLong(left)+1;}catch(NumberFormatException ignored){return false;}
        return left.length()==1&&right.length()==1&&Character.toLowerCase(right.charAt(0))==Character.toLowerCase(left.charAt(0))+1;
    }
    private static boolean isFirst(String item) {return "1".equals(item)||"a".equalsIgnoreCase(item)||"i".equalsIgnoreCase(item);}
    private static int qualificationPriority(Chunk c) {
        String text=nvl(c.getContent()).toLowerCase(Locale.ROOT);
        if(text.matches("(?s).*\\b(?:except|unless|provided that)\\b.*"))return 2;
        return INTRO.matcher(text).find()?1:0;
    }

    private boolean add(Pack pack,Map<String,Chunk> selected,Chunk origin,Chunk target,String relation,boolean required,int limit) {
        if(selected.containsKey(target.getId()))return true;
        LinkTrace trace=new LinkTrace();trace.setOriginId(origin==null?null:origin.getId());trace.setTargetId(target.getId());trace.setRelation(relation);trace.setRequired(required);trace.setIncrementalChars(nvl(target.getContent()).length());
        if(pack.getContentChars()+trace.getIncrementalChars()>limit) {
            trace.setStatus("dropped_budget");pack.getOmittedSupportIds().add(target.getId());if(required)markUnresolved(pack,origin==null?target.getId():origin.getId());pack.getLinks().add(trace);return false;
        }
        trace.setStatus("submitted");selected.put(target.getId(),target);pack.setContentChars(pack.getContentChars()+trace.getIncrementalChars());pack.getLinks().add(trace);return true;
    }
    private void unresolved(Pack pack,Chunk origin,String ref,String reason) {
        String warning=ref+" ("+reason+")";if(!pack.getUnresolvedReferences().contains(warning))pack.getUnresolvedReferences().add(warning);
        markUnresolved(pack,origin.getId());
        if(pack.getLinks().stream().anyMatch(l->origin.getId().equals(l.getOriginId())&&ref.equals(l.getReferenceId())&&reason.equals(l.getStatus())))return;
        LinkTrace trace=new LinkTrace();trace.setOriginId(origin.getId());trace.setReferenceId(ref);trace.setRelation("explicit_reference");trace.setStatus(reason);trace.setRequired(true);pack.getLinks().add(trace);
    }
    private static void markUnresolved(Pack pack,String id) {if(!pack.getUnresolvedComparisonIds().contains(id))pack.getUnresolvedComparisonIds().add(id);}
    private static int cost(Collection<Chunk> chunks,Map<String,Chunk> selected) {return chunks.stream().filter(c->!selected.containsKey(c.getId())).mapToInt(c->nvl(c.getContent()).length()).sum();}
    private Chunk original(Chunk supplied) {
        Chunk c=supplied==null?null:byId.get(supplied.getId());
        if(c==null||!Objects.equals(c.getSourceHash(),supplied.getSourceHash())||!Objects.equals(c.getContent(),supplied.getContent())
                ||!Objects.equals(c.getDocumentId(),supplied.getDocumentId())||!Objects.equals(c.getRole(),supplied.getRole()))throw new IllegalArgumentException("Retrieved chunk is outside the immutable corpus snapshot");
        return c;
    }
    private Set<String> references(Chunk source) {
        Set<String> refs=new LinkedHashSet<>();if(reference==null)return refs;Matcher m=reference.matcher(nvl(source.getContent()));
        while(m.find()) {
            String ref=canonical(m.group(1)+m.group(3)),number=canonical(m.group(3));
            if(m.group(2)==null&&!number.contains(".")&&!number.contains("(")&&clauses.keySet().stream().noneMatch(c->matches(ref,c)))continue;
            if(!ref.equals(canonical(source.getClauseId())))refs.add(ref);
        }return refs;
    }
    private Target target(String ref,Chunk origin) {
        List<Chunk> found=new ArrayList<>(),parentScopes=new ArrayList<>();
        for(Map.Entry<String,List<Chunk>> e:clauses.entrySet()) {
            if(matches(ref,e.getKey()))found.addAll(e.getValue());
            else if(parentReference(ref,e.getKey()))parentScopes.addAll(e.getValue());
        }
        if(found.isEmpty()&&!parentScopes.isEmpty()) {
            for(Chunk c:parentScopes)if(subparagraphLocated(ref,c))found.add(c);
            if(found.isEmpty())return new Target(Collections.emptyList(),"parent_scope_candidate_subparagraph_not_located");
        }
        if(found.isEmpty())return new Target(found,"target_not_located");
        Set<String> requestedEditions=referenceEditions(ref,origin);
        if(requestedEditions.size()>1)return new Target(Collections.emptyList(),"ambiguous_explicit_edition");
        if(requestedEditions.size()==1) {
            String year=requestedEditions.iterator().next();
            found.removeIf(c->!year.equals(sourceEditions.get(sourceIdentity(c))));
            if(found.isEmpty())return new Target(found,"explicit_edition_target_not_located");
        }
        if(found.stream().anyMatch(c->sourceIdentity(c).equals(sourceIdentity(origin))))found.removeIf(c->!sourceIdentity(c).equals(sourceIdentity(origin)));
        else if(found.stream().anyMatch(c->"tender".equals(c.getRole())))found.removeIf(c->!"tender".equals(c.getRole()));
        Set<String> distinct=found.stream().map(VettingReviewPackBuilder::targetIdentity).collect(Collectors.toSet());
        if(distinct.size()>1)return new Target(Collections.emptyList(),"ambiguous_source_or_edition");
        return new Target(found,"resolved");
    }
    private Chunk focus(List<Chunk> targets,Chunk origin,Map<String,Integer> rank) {
        Set<String> terms=terms(origin.getContent());
        return targets.stream().min(Comparator.<Chunk>comparingInt(c->rank.getOrDefault(c.getId(),Integer.MAX_VALUE))
                .thenComparing(Comparator.comparingInt((Chunk c)-> {Set<String> overlap=terms(c.getContent());overlap.retainAll(terms);return overlap.size();}).reversed())
                .thenComparingInt(c->sourceOrder.get(c.getId()))).orElseThrow(IllegalStateException::new);
    }
    private Chunk related(Chunk support,Collection<Chunk> selected) {
        for(Chunk c:selected)if("tender".equals(c.getRole())) {
            if("project_fact".equals(support.getRole())&&references(support).stream().anyMatch(ref->target(ref,support).chunks.stream().anyMatch(t->sameScope(t,c))))return c;
            if("package_manifest".equals(support.getRole())&&!nvl(c.getFileName()).isEmpty()&&support.getContent().toLowerCase(Locale.ROOT).contains(c.getFileName().toLowerCase(Locale.ROOT)))return c;
            if("standard".equals(support.getRole())&&references(c).stream().anyMatch(ref->target(ref,c).chunks.stream().anyMatch(t->t.getId().equals(support.getId()))))return c;
        }return null;
    }
    private boolean usable(Chunk c) {
        String text=nvl(c.getContent());if(text.trim().isEmpty())return false;
        if(c.getParts()!=null&&!c.getParts().isEmpty()&&c.getParts().stream().allMatch(p->nvl(p.getAnchor()).matches("(?:header|footer)/.*")))return false;
        List<String> lines=Arrays.stream(text.split("\\r?\\n")).map(String::trim).filter(l->!l.isEmpty()).collect(Collectors.toList());
        if(lines.stream().filter(l->l.matches(".*\\.{3,}\\s*\\d*\\s*$")).count()*2>=lines.size())return false;
        if(OBLIGATION.matcher(text).find())return true;
        Set<String> distinct=new HashSet<>();int titled=0,numeric=0;boolean contents=false;
        for(String line:lines) {
            contents|=line.matches("(?i)(?:table\\s+of\\s+)?contents?(?:\\s*\\(.*\\))?");if(line.matches("\\.?\\d+(?:\\.\\d+)*"))numeric++;
            if(reference!=null) {Matcher m=reference.matcher(line);if(m.lookingAt()&&line.substring(m.end()).trim().matches("[A-Za-z].*\\s+\\d+")){titled++;distinct.add(canonical(m.group(1)+m.group(3)));}}
        }
        return !(distinct.size()>=3&&titled*2>=lines.size())&&!(contents&&lines.size()>=6&&numeric>=3&&numeric*4>=lines.size());
    }
    private static Set<String> terms(String text) {return Arrays.stream(nvl(text).toLowerCase(Locale.ROOT).split("[^a-z0-9]+" )).filter(t->t.length()>3).collect(Collectors.toSet());}
    private static String scope(Chunk c) {return c.getClauseHeadingLocation()==null?null:c.getDocumentId()+"|"+nvl(c.getSourceHash())+"|"+c.getClauseHeadingLocation();}
    private static String sourceIdentity(Chunk c) {return c.getDocumentId()+"|"+nvl(c.getSourceHash());}
    private static boolean sameScope(Chunk a,Chunk b) {return a.getId().equals(b.getId())||(scope(a)!=null&&scope(a).equals(scope(b)));}
    private static String targetIdentity(Chunk c) {return c.getDocumentId()+"|"+nvl(c.getSourceHash())+"|"+nvl(c.getClauseHeadingLocation());}
    private static boolean ownedClause(Chunk c) {
        String owner=canonical(c.getFileKey());return !owner.isEmpty()&&!"OTHER".equals(owner)
                &&canonical(c.getClauseId()).matches(Pattern.quote(owner)+"(?:\\.[A-Z]+)?\\.?\\d.*");
    }
    private static boolean matches(String ref,String clause) {return ref.equals(clause)||clause.matches(Pattern.quote(ref)+"\\.[A-Z]");}
    private static boolean parentReference(String ref,String clause) {return ref.startsWith(clause.replaceFirst("\\.[A-Z]$","")+"(");}
    private static boolean subparagraphLocated(String ref,Chunk candidate) {
        String parent=canonical(candidate.getClauseId()).replaceFirst("\\.[A-Z]$","");String suffix=ref.substring(parent.length());
        Matcher labels=Pattern.compile("\\(([A-Z0-9]+)\\)").matcher(suffix);StringBuilder pattern=new StringBuilder("(?im)^\\s*");int end=0;
        while(labels.find()) {if(labels.start()!=end)return false;pattern.append("\\(\\s*").append(Pattern.quote(labels.group(1))).append("\\s*\\)\\s*");end=labels.end();}
        if(end!=suffix.length()||end==0)return false;
        return Pattern.compile(pattern+"(?=\\S|$)").matcher(nvl(candidate.getContent())).find();
    }
    private static Set<String> editions(String text) {
        Set<String> years=new LinkedHashSet<>();Matcher m=EDITION.matcher(nvl(text));while(m.find())years.add(m.group(1)!=null?m.group(1):m.group(2));return years;
    }
    private Set<String> referenceEditions(String ref,Chunk origin) {
        Set<String> years=new LinkedHashSet<>();if(reference==null)return years;
        // Associate an edition with the sentence containing this reference; years elsewhere cannot select a version.
        for(String sentence:nvl(origin.getContent()).split("\\r?\\n|(?<=[.!?;])\\s+(?=[A-Z])")) {
            Matcher m=reference.matcher(sentence);while(m.find())if(ref.equals(canonical(m.group(1)+m.group(3))))years.addAll(editions(sentence));
        }return years;
    }
    private static String canonical(String value) {return nvl(value).replaceAll("\\s+","").toUpperCase(Locale.ROOT);}
    private static String nvl(String value) {return value==null?"":value;}
}
