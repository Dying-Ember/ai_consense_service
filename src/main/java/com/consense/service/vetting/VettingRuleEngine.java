package com.consense.service.vetting;

import com.consense.document.DocumentBlock;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.*;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, source-only coordination checks. No answer sheet, clause allowlist or model is used.
 * Coverage counts are exhaustive traversal counts, not a claim that every possible defect is detected.
 */
public final class VettingRuleEngine {
    private static final String NUMBER = "\\d+(?:\\.\\d+)*(?:\\s*\\([a-z0-9]+\\))*";
    private static final Pattern NUMBER_PATTERN = Pattern.compile(NUMBER, Pattern.CASE_INSENSITIVE);
    private static final Pattern GCC_TARGET = Pattern.compile("\\bClauses?\\s+(" + NUMBER
            + "(?:\\s*(?:,|and)\\s*" + NUMBER + ")*)\\s+of\\s+the\\s+General\\s+Conditions\\s+of\\s+Contract", Pattern.CASE_INSENSITIVE);
    private static final Pattern GCC_REF = Pattern.compile("\\bGCC\\s*(?:Clauses?\\s*)?(" + NUMBER + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("(?im)^\\s*((?:NTT|SCT|SCC|GCC|GCT|PRE|SL|FT|AA)\\s*\\.?\\s*(?:[A-Z]+\\d*\\.)?\\d+(?:\\.\\d+)*(?:\\([a-z0-9]+\\))*)");
    private static final Pattern NOT_USED = Pattern.compile("(\\([a-z0-9]+\\)(?:\\s*\\([a-z0-9]+\\))*)\\s*Not\\s+used\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_DELETION = Pattern.compile("^\\s+(?:is|are)\\s+deleted\\b(?:[^\\n]{0,120})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBSTITUTION = Pattern.compile("^\\s+(?:is|are)\\s+amended\\s+by\\s+substituting", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFINITION = Pattern.compile("[\"“]([^\"”\\n]{2,100})[\"”]\\s+(?:means|shall\\s+mean)\\s+([^\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final String COUNT = "(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten)";
    private static final String MINIMUM = "(?:at\\s+least|not\\s+less\\s+than|(?:a\\s+)?minimum(?:\\s+of)?)";
    private static final String ROLE = "[A-Za-z][A-Za-z-]*(?:[ \\t]+[A-Za-z][A-Za-z-]*){0,12}[ \\t]+(?:Co-?ordinator|Coordinator|Manager|Engineer|Foreman|Officer|Supervisor|Representative)";
    private static final Pattern ROLE_NAME = Pattern.compile("(" + ROLE + ")(?:\\s*\\(([A-Z]{2,10})\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_BEFORE = Pattern.compile("(?<![\\w.(])(?:(" + MINIMUM + ")\\s+)?(" + COUNT
            + ")(?:\\s*\\(\\s*\\d+\\s*\\))?\\s+(?:numbers?\\s+of\\s+)?(" + ROLE
            + ")(?:\\s*\\(([A-Z]{2,10})\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_AFTER = Pattern.compile("(" + ROLE + ")(?:\\s*\\(([A-Z]{2,10})\\))?"
            + "\\s*[:|,]?\\s*(?:(" + MINIMUM + "|total|exactly)\\s+)?(" + COUNT
            + ")(?:\\s*\\(\\s*\\d+\\s*\\))?\\s*(?:nos?\\.?|numbers?|persons?|staff)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACRONYM_AFTER = Pattern.compile("\\b([A-Z]{2,10})\\b\\s*[:|,]?\\s+(?:(" + MINIMUM
            + "|total|exactly)\\s+)?(" + COUNT + ")(?:\\s*\\(\\s*\\d+\\s*\\))?\\s*(?:nos?\\.?|numbers?|persons?|staff)\\b");
    private static final String NAMESPACE = "[A-Z]{1,12}(?:\\.[A-Z]{1,6})?";
    private static final Pattern NUMBERED_NOT_USED = Pattern.compile("^\\s*(" + NAMESPACE + ")(" + NUMBER
            + ")\\s*Not\\s+used\\s*[.;]?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAUSE_RANGE = Pattern.compile("\\bClauses?\\s+(" + NAMESPACE + ")(" + NUMBER
            + ")\\s*(?:\\bto\\b|\\bthrough\\b|[–—-])\\s*(" + NAMESPACE + ")?(" + NUMBER + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_ACRONYM = Pattern.compile("([A-Z][A-Za-z’'-]*(?:[ \\t]+(?:[A-Z][A-Za-z’'-]*|of|and|for|the|to|in)){1,9})[ \\t]*\\(([A-Z][A-Za-z0-9]{1,11})\\)");
    private static final Pattern DASH_ACRONYM = Pattern.compile("^\\s*([A-Z][A-Za-z0-9]{1,11})(?:[ \\t]+[-–—:][ \\t]*|[ \\t]*[-–—:][ \\t]+)([A-Z][^\\n|]{2,200})\\s*$");
    private static final Pattern ABBREVIATION_HEADING = Pattern.compile("(?i)^(?:list\\s+of\\s+)?(?:abbreviations|short\\s+forms|acronyms|definitions\\s+and\\s+abbreviations)\\s*[:.]?$");
    private static final Pattern ABBREVIATION_INTRO = Pattern.compile("(?i)^(?:the\\s+following\\s+)?(?:abbreviations|short\\s+forms|acronyms)\\s+(?:used\\s+)?(?:have\\s+the\\s+following\\s+meanings|shall\\s+be\\s+interpreted\\s+as\\s+follows|and\\s+their\\s+meanings)\\s*[:.-]*$");
    private static final Pattern ABBREVIATION_SCOPE = Pattern.compile("(?i)^\\s*("+NAMESPACE+")\\s*("+NUMBER+")(?:\\.([A-Z])(?=\\s|$))?(?:\\s+|(?=[A-Z][a-z])|$)");
    private static final Pattern EXPANSION_WORD = Pattern.compile("[A-Za-z]+(?:['’][A-Za-z]+)?");

    public List<Output> review(List<Source> sources) { return scan(sources).getOutputs(); }

    public ReviewResult scan(List<Source> sources) {
        List<Corpus> corpora = new ArrayList<>();
        int blocks = 0, characters = 0;
        if (sources != null) for (Source source : sources) {
            if (source == null) continue;
            Corpus corpus = new Corpus(source);
            corpora.add(corpus);
            blocks += corpus.units.size();
            for (Unit unit : corpus.units) characters += unit.block.getText().length();
        }
        List<Output> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        deletedReferences(corpora, output, seen);
        definitions(corpora, output, seen);
        personnelCounts(corpora, output, seen);
        inactiveClauseRanges(corpora, output, seen);
        abbreviationAmbiguities(corpora, output, seen);
        return new ReviewResult(output, corpora.size(), blocks, characters);
    }

    private void inactiveClauseRanges(List<Corpus> corpora, List<Output> output, Set<String> seen) {
        Map<String, List<InactiveClause>> inactive = new LinkedHashMap<>();
        for (Corpus corpus : corpora) for (Unit unit : corpus.units) {
            Matcher heading = NUMBERED_NOT_USED.matcher(unit.block.getText());
            if (!heading.matches() || !ownsNamespace(corpus.source, heading.group(1)) || contentsBlock(unit.block)) continue;
            ClauseId id = new ClauseId(heading.group(1), heading.group(2));
            inactive.computeIfAbsent(id.key(), key -> new ArrayList<>()).add(new InactiveClause(id, corpus, unit));
        }
        for (Corpus corpus : corpora) for (Unit unit : corpus.units) {
            Matcher range = CLAUSE_RANGE.matcher(unit.block.getText());
            while (range.find()) {
                ClauseId first = new ClauseId(range.group(1), range.group(2));
                ClauseId last = new ClauseId(range.group(3) == null ? range.group(1) : range.group(3), range.group(4));
                List<InactiveClause> included = new ArrayList<>();
                for (List<InactiveClause> candidates : inactive.values()) {
                    if (!candidates.get(0).id.between(first, last)) continue;
                    InactiveClause selected = candidates.get(0);
                    for (InactiveClause candidate : candidates) {
                        if (Objects.equals(candidate.corpus.source.id, corpus.source.id)) { selected = candidate; break; }
                    }
                    included.add(selected);
                }
                if (included.isEmpty()) continue;
                String identity = "inactive-range:" + corpus.source.id + ":" + unit.block.getId() + ":" + first.key() + ":" + last.key();
                if (!seen.add(identity)) continue;
                List<String> names = new ArrayList<>();
                List<Evidence> evidence = corpus.evidence(unit.start, unit.end);
                for (InactiveClause clause : included) {
                    names.add(clause.id.key()); evidence.addAll(clause.corpus.evidence(clause.unit.start, clause.unit.end));
                }
                output.add(new Output("inactive_clause_range_coordination", "Clause range includes numbered provisions marked Not used",
                        "The inclusive reference " + first.key() + " to " + last.key() + " includes " + String.join(", ", names)
                                + ", explicitly marked Not used. This does not establish that an inactive provision has been restored; the intended scope and precision of the range need confirmation.",
                        "Check whether the reference should list only the applicable clauses or expressly exclude inactive entries. Preserve the Not used status unless an authorised amendment changes it.",
                        "medium", "reference", unique(evidence)));
            }
        }
    }

    private static boolean ownsNamespace(Source source, String namespace) {
        if (source.fileKey == null || source.fileKey.trim().isEmpty() || "OTHER".equalsIgnoreCase(source.fileKey)) return false;
        String owner = canonical(source.fileKey), label = canonical(namespace);
        return label.equals(owner) || label.startsWith(owner + ".");
    }

    private static boolean contentsBlock(DocumentBlock block) {
        return "toc".equalsIgnoreCase(block.getKind()) || (block.getParagraphStyleName() != null
                && block.getParagraphStyleName().trim().matches("(?i)(?:toc|contents|table\\s+of\\s+contents)(?:\\s*\\d+)?"));
    }

    private void abbreviationAmbiguities(List<Corpus> corpora, List<Output> output, Set<String> seen) {
        Map<String, List<Abbreviation>> byShortForm = new LinkedHashMap<>();
        for (Corpus corpus : corpora) {
            Map<Unit, AbbreviationContext> contexts = abbreviationContexts(corpus);
            for (Unit unit : corpus.units) {
                AbbreviationContext context = contexts.get(unit);
                Matcher name = NAME_ACRONYM.matcher(unit.block.getText());
                while (name.find()) addAbbreviation(byShortForm, name.group(2), name.group(1), false, corpus, unit, context);
                DocumentBlock block = unit.block;
                List<String> cells = block.getCells();
                if ("table_row".equals(block.getKind()) && cells != null && cells.size() >= 2 && cells.size() <= 3) {
                    String shortForm = cells.get(0).trim();
                    String expansion = String.join(" ", cells.subList(1, cells.size())).trim();
                    if (context.glossary && expansion.matches("^[-–—:].*")) {
                        addAbbreviation(byShortForm, shortForm, expansion.replaceFirst("^[-–—:]\\s*", ""), true, corpus, unit, context);
                    }
                } else {
                    Matcher dash = DASH_ACRONYM.matcher(block.getText());
                    if (context.glossary && dash.matches()) addAbbreviation(byShortForm, dash.group(1), dash.group(2), true, corpus, unit, context);
                }
            }
        }
        for (Map.Entry<String, List<Abbreviation>> entry : byShortForm.entrySet()) {
            List<Abbreviation> values = entry.getValue();
            for (int i = 0; i < values.size(); i++) for (int j = i + 1; j < values.size(); j++) {
                Abbreviation a = values.get(i), b = values.get(j);
                // Require an explicit glossary-style expansion; casual repeated parenthetical names are insufficient.
                if ((!a.glossary && !b.glossary) || a.scope==null || !a.scope.equals(b.scope) || compatibleExpansion(a.name, b.name)) continue;
                List<String> names = new ArrayList<>(Arrays.asList(normalize(a.name), normalize(b.name))); Collections.sort(names);
                if (!seen.add("abbreviation:" + a.scope + ":" + entry.getKey() + ":" + String.join("|", names))) continue;
                List<Evidence> evidence = a.corpus.evidence(a.unit.start, a.unit.end);
                evidence.addAll(b.corpus.evidence(b.unit.start, b.unit.end));
                output.add(new Output("abbreviation_ambiguity", "Abbreviation " + entry.getKey() + " has different explicit expansions",
                        entry.getKey() + " is explicitly expanded as " + a.name + " and " + b.name
                                + " within the same identified source scope " + a.scopeLabel
                                + ". Confirm the intended meaning and whether qualifications distinguish these definitions. Different expansions alone do not establish conflicting contract obligations.",
                        "Review both original definitions in this scope. Where the intended meanings differ, use distinct short forms or an explicit qualification; retain separate scoped definitions where their context resolves the distinction.",
                        "medium", "language", unique(evidence)));
            }
        }
    }

    private void addAbbreviation(Map<String, List<Abbreviation>> values, String shortForm, String expansion,
                                 boolean glossary, Corpus corpus, Unit unit, AbbreviationContext context) {
        if (!shortForm.matches("[A-Z][A-Za-z0-9]{1,11}")) return;
        int capitals = 0; for (char c : shortForm.toCharArray()) if (Character.isUpperCase(c)) capitals++;
        if (capitals < 2) return;
        String name = expansion.trim().replaceFirst("[.;:]\\s*$", "").replaceFirst("^The\\s+", "");
        if (name.length() > 200 || !name.matches("[A-Z][A-Za-z’'(), /&-]+")) return;
        if (name.matches("(?is).*\\b(?:shall|must|may|will|means|is|are|has|have|where|when|which)\\b.*")) return;
        Matcher words=EXPANSION_WORD.matcher(name);int completeWords=0;
        while(words.find())if(words.group().length()>=2)completeWords++;
        if(completeWords<2||unit.block.getText().trim().length()<12)return;
        if (!glossary) {
            StringBuilder initials = new StringBuilder();
            for (String word : name.split("\\s+")) {
                if (!word.matches("(?i)of|and|for|the|to|in")) initials.append(Character.toUpperCase(word.charAt(0)));
            }
            if (!initials.toString().equals(shortForm.toUpperCase(Locale.ROOT))) return;
        }
        List<Abbreviation> list = values.computeIfAbsent(shortForm.toUpperCase(Locale.ROOT), k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            Abbreviation old = list.get(i);
            if (compatibleExpansion(old.name,name) && old.glossary == glossary && Objects.equals(old.scope,context.scope)
                    && old.corpus.source.id.equals(corpus.source.id)) {
                // Prefer the shortest original passage when an expansion is repeated in long personnel lists.
                if (unit.block.getText().length() < old.unit.block.getText().length()) list.set(i, new Abbreviation(name, glossary, corpus, unit, context.scope, context.label));
                return;
            }
        }
        list.add(new Abbreviation(name, glossary, corpus, unit, context.scope, context.label));
    }

    private boolean compatibleExpansion(String a, String b) {
        String left = expansionMeaning(a), right = expansionMeaning(b);
        return (" " + left + " ").contains(" " + right + " ") || (" " + right + " ").contains(" " + left + " ");
    }

    private String expansionMeaning(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[-–—]", "").replaceAll("[^a-z]+", " ")
                .replaceAll("\\b(?:and|the|of|for|to|in)\\b", " ").replaceAll("\\b([a-z]{4,})s\\b", "$1")
                .replaceAll("\\s+", " ").trim();
    }

    /** Only explicit clause/headings or an actual table establish a shared definition scope. */
    private Map<Unit,AbbreviationContext> abbreviationContexts(Corpus corpus) {
        Map<Unit,AbbreviationContext> result=new HashMap<>();String scope=null,label=null;boolean glossary=false;
        Integer limitedPage=null;
        Set<String> glossaryTables = new HashSet<>();
        for(Unit unit:corpus.units) {
            DocumentBlock block=unit.block;String text=block.getText().trim();
            if(limitedPage!=null&&!Objects.equals(limitedPage,block.getPageNo())) {scope=null;label=null;glossary=false;limitedPage=null;}
            Matcher heading=ABBREVIATION_SCOPE.matcher(text);String headingText=text;
            boolean owned=heading.find()&&ownsNamespace(corpus.source,heading.group(1));
            if(owned) {
                String clause=canonical(heading.group(1)+heading.group(2)+(heading.group(3)==null?"":"."+heading.group(3)));
                scope=corpus.source.id+"|clause:"+clause;label=corpus.source.fileName+" / "+clause;glossary=false;limitedPage=null;
                headingText=text.substring(heading.end()).trim();
            } else if((block.getParagraphStyleName()!=null&&block.getParagraphStyleName().matches("(?i).*(?:heading|title).*"))||"heading".equals(block.getKind())) {
                scope=corpus.source.id+"|heading:"+block.getId();label=corpus.source.fileName+" / "+block.getLocation();glossary=false;limitedPage=null;
            }
            if(ABBREVIATION_HEADING.matcher(headingText).matches() || ABBREVIATION_INTRO.matcher(headingText).matches()) {
                if(!owned) {scope=corpus.source.id+"|glossary:"+block.getId();label=corpus.source.fileName+" / "+block.getLocation();limitedPage=block.getPageNo();}
                glossary=true;
            }
            String tableScope=tableParent(block);
            if (tableScope != null && abbreviationTableHeader(block)) glossaryTables.add(tableScope);
            String effectiveScope=scope!=null?scope:tableScope==null?null:corpus.source.id+"|table:"+tableScope;
            String effectiveLabel=label!=null?label:tableScope==null?null:corpus.source.fileName+" / "+tableScope;
            result.put(unit,new AbbreviationContext(effectiveScope,effectiveLabel,glossary || glossaryTables.contains(tableScope)));
        }
        return result;
    }
    private static String tableParent(DocumentBlock block) {
        if(!"table_row".equals(block.getKind())||block.getLocation()==null||!block.getLocation().contains("/table-row/"))return null;
        return block.getLocation().substring(0,block.getLocation().indexOf("/table-row/"));
    }
    private static boolean abbreviationTableHeader(DocumentBlock block) {
        List<String> cells = block.getCells();
        return cells != null && cells.size() >= 2 && cells.size() <= 3
                && cells.get(0).trim().matches("(?i)(?:abbreviation|short\\s+form|acronym)s?")
                && String.join(" ", cells.subList(1, cells.size())).trim().matches("(?i)(?:expansion|meaning|full\\s+(?:form|name)|description)s?");
    }

    private static final class ClauseId {
        private final String namespace, number, shape;
        private final List<String> components = new ArrayList<>();
        private ClauseId(String namespace, String number) {
            this.namespace = canonical(namespace); this.number = canonical(number);
            this.shape = this.number.replaceAll("\\d+|[A-Z]+", "#");
            Matcher part = Pattern.compile("\\d+|[A-Z]+").matcher(this.number);
            while (part.find()) components.add(part.group().matches("\\d+") ? new BigInteger(part.group()).toString() : part.group());
        }
        private String key() { return namespace + number; }
        private boolean between(ClauseId first, ClauseId last) {
            if (!namespace.equals(first.namespace) || !namespace.equals(last.namespace)
                    || !shape.equals(first.shape) || !shape.equals(last.shape) || components.isEmpty()
                    || components.size() != first.components.size() || components.size() != last.components.size()) return false;
            int end = components.size() - 1;
            for (int i = 0; i < end; i++) if (!components.get(i).equals(first.components.get(i)) || !components.get(i).equals(last.components.get(i))) return false;
            String value = components.get(end), lower = first.components.get(end), upper = last.components.get(end);
            if (value.matches("\\d+") && lower.matches("\\d+") && upper.matches("\\d+")) {
                BigInteger n = new BigInteger(value);
                return n.compareTo(new BigInteger(lower)) >= 0 && n.compareTo(new BigInteger(upper)) <= 0;
            }
            if (roman(lower) && roman(upper)) {
                return roman(value) && romanValue(value) >= romanValue(lower) && romanValue(value) <= romanValue(upper);
            }
            return value.matches("[A-Z]") && lower.matches("[A-Z]") && upper.matches("[A-Z]")
                    && value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
        }
        private static boolean roman(String value) {
            return value.matches("(?=.)M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})");
        }
        private static int romanValue(String value) {
            int result = 0, previous = 0;
            for (int i = value.length() - 1; i >= 0; i--) {
                int n = "IVXLCDM".indexOf(value.charAt(i));
                int current = new int[]{1,5,10,50,100,500,1000}[n];
                result += current < previous ? -current : current; previous = current;
            }
            return result;
        }
    }
    @AllArgsConstructor private static final class InactiveClause { private final ClauseId id; private final Corpus corpus; private final Unit unit; }
    @AllArgsConstructor private static final class Abbreviation { private final String name; private final boolean glossary; private final Corpus corpus; private final Unit unit; private final String scope,scopeLabel; }
    @AllArgsConstructor private static final class AbbreviationContext { private final String scope,label; private final boolean glossary; }

    private void deletedReferences(List<Corpus> corpora, List<Output> output, Set<String> seen) {
        List<Deletion> deletions = new ArrayList<>();
        for (Corpus corpus : corpora) {
            if (!"SCC".equalsIgnoreCase(corpus.source.fileKey)) continue;
            Matcher amendment = GCC_TARGET.matcher(corpus.text);
            while (amendment.find()) {
                List<String> targets = numbers(amendment.group(1));
                Matcher direct = DIRECT_DELETION.matcher(corpus.text.substring(amendment.end()));
                if (direct.find()) {
                    for (String target : targets) deletions.add(new Deletion("GCC" + target,
                            corpus.evidence(amendment.start(), amendment.end() + direct.end())));
                } else if (SUBSTITUTION.matcher(corpus.text.substring(amendment.end())).find()) {
                    int end = nextSection(corpus.text, amendment.end());
                    Matcher inactive = NOT_USED.matcher(corpus.text);
                    inactive.region(amendment.end(), end);
                    while (inactive.find()) {
                        String suffix = canonical(inactive.group(1));
                        String target = null;
                        for (String candidate : targets) if (candidate.endsWith(suffix)) target = candidate;
                        if (target == null && targets.size() == 1) target = targets.get(0) + suffix;
                        if (target != null) {
                            List<Evidence> evidence = corpus.evidence(amendment.start(), amendment.end());
                            evidence.addAll(corpus.evidence(inactive.start(), inactive.end()));
                            deletions.add(new Deletion("GCC" + target, unique(evidence)));
                        }
                    }
                }
            }
        }
        for (Deletion deletion : deletions) for (Corpus corpus : corpora) {
            // The retained original GCC is a baseline, not a tender document containing an unresolved reference.
            if ("GCC".equalsIgnoreCase(corpus.source.fileKey)) continue;
            tableDeletedReferences(deletion, corpus, output, seen);
            Matcher explicit = GCC_REF.matcher(corpus.text);
            while (explicit.find()) addDeletedReference(deletion, corpus, canonical("GCC" + explicit.group(1)),
                    explicit.start(), explicit.end(), output, seen);
            Matcher named = GCC_TARGET.matcher(corpus.text);
            while (named.find()) {
                String rest = corpus.text.substring(named.end(), Math.min(corpus.text.length(), named.end() + 140));
                if ("SCC".equalsIgnoreCase(corpus.source.fileKey)
                        && (rest.matches("(?is)^\\s+(?:is|are)\\s+(?:deleted|amended).*"))) continue;
                for (String number : numbers(named.group(1))) addDeletedReference(deletion, corpus, "GCC" + number,
                        named.start(), named.end(), output, seen);
            }
        }
    }

    /** Bare clause numbers acquire a GCC namespace only from their own table header. */
    private void tableDeletedReferences(Deletion deletion, Corpus corpus, List<Output> output, Set<String> seen) {
        Map<String, Unit> headers = new HashMap<>();
        Map<String, Integer> columns = new HashMap<>();
        for (Unit unit : corpus.units) {
            DocumentBlock block = unit.block;
            String location = block.getLocation() == null ? "" : block.getLocation();
            if (!"table_row".equals(block.getKind()) || !location.matches(".*/(?:table-row|row)/\\d+$")) continue;
            String table = location.replaceFirst("/(?:table-row|row)/\\d+$", "");
            List<String> cells = block.getCells();
            if (cells == null || cells.isEmpty()) cells = Arrays.asList(block.getText().split("\\s*\\|\\s*", -1));
            int headerColumn = -1;
            for (int i = 0; i < cells.size(); i++) {
                if (cells.get(i).trim().matches("(?i)GCC\\s+Clauses?\\s+(?:No\\.?|Number)(?:\\s.*)?")) headerColumn = i;
            }
            if (headerColumn >= 0) { headers.put(table, unit); columns.put(table, headerColumn); continue; }
            Integer column = columns.get(table);
            if (column == null || column >= cells.size()) continue;
            Matcher number = Pattern.compile("^\\s*(" + NUMBER + ")(?:\\s|$)", Pattern.CASE_INSENSITIVE).matcher(cells.get(column));
            if (!number.find()) continue;
            String reference = "GCC" + canonical(number.group(1));
            if (!(reference.equals(deletion.target) || reference.startsWith(deletion.target + "("))) continue;
            String key = "deleted:" + deletion.target + ":" + corpus.source.id + ":" + block.getId();
            if (!seen.add(key)) continue;
            List<Evidence> evidence = new ArrayList<>(deletion.evidence);
            Unit header = headers.get(table);
            evidence.addAll(corpus.evidence(header.start, header.end));
            evidence.addAll(corpus.evidence(unit.start, unit.end));
            output.add(new Output("deleted_clause_reference", "Reference to a deleted contract provision",
                    reference + " remains in a table labelled GCC Clause No. although the SCC deletes it or replaces it with Not used.",
                    "Confirm the intended effective provision and update the table row; review the amendment, table header and retained entry together.",
                    "high", "reference", unique(evidence)));
        }
    }

    private void addDeletedReference(Deletion deletion, Corpus corpus, String reference, int start, int end,
                                     List<Output> output, Set<String> seen) {
        if (!(reference.equals(deletion.target) || reference.startsWith(deletion.target + "("))) return;
        List<Evidence> referenceEvidence = corpus.evidence(start, end);
        if (referenceEvidence.isEmpty()) return;
        String key = "deleted:" + deletion.target + ":" + corpus.source.id + ":" + referenceEvidence.get(0).blockId;
        if (!seen.add(key)) return;
        List<Evidence> evidence = new ArrayList<>(deletion.evidence);
        evidence.addAll(referenceEvidence);
        output.add(new Output("deleted_clause_reference", "Reference to a deleted contract provision",
                reference + " remains cited although the SCC deletes it or replaces it with Not used.",
                "Confirm the intended effective provision and update the referencing text; retain both source passages for review.",
                "high", "reference", unique(evidence)));
    }

    private int nextSection(String text, int start) {
        int end = text.length();
        Matcher heading = HEADING.matcher(text);
        if (heading.find(start)) end = heading.start();
        Matcher amendment = GCC_TARGET.matcher(text);
        if (amendment.find(start)) end = Math.min(end, amendment.start());
        return end;
    }

    private void definitions(List<Corpus> corpora, List<Output> output, Set<String> seen) {
        Map<String, List<Definition>> byTerm = new LinkedHashMap<>();
        for (Corpus corpus : corpora) {
            Matcher matcher = DEFINITION.matcher(corpus.text);
            while (matcher.find()) byTerm.computeIfAbsent(normalize(matcher.group(1)), k -> new ArrayList<>())
                    .add(new Definition(matcher.group(1), normalize(matcher.group(2)), corpus,
                            matcher.start(), matcher.end()));
        }
        for (List<Definition> values : byTerm.values()) for (int i = 0; i < values.size(); i++) {
            for (int j = i + 1; j < values.size(); j++) {
                Definition a = values.get(i), b = values.get(j);
                if (a.meaning.equals(b.meaning) || localDefinition(a) || localDefinition(b) || intentionalOverride(a, b)) continue;
                String key = "definition:" + normalize(a.term) + ":" + a.corpus.source.id + ":" + b.corpus.source.id;
                if (!seen.add(key)) continue;
                List<Evidence> evidence = a.corpus.evidence(a.start, a.end);
                evidence.addAll(b.corpus.evidence(b.start, b.end));
                output.add(new Output("definition_coordination", "Definitions of " + a.term + " require coordination",
                        "The same named term has different definition text. The difference may be intentional or limited to a particular scope; it requires confirmation.",
                        "Confirm the intended role and scope in both provisions. Clarify the distinction or align the definitions where one meaning is intended.",
                        "medium", "language", unique(evidence)));
            }
        }
    }

    private boolean localDefinition(Definition definition) {
        int start = 0;
        Matcher heading = HEADING.matcher(definition.corpus.text);
        while (heading.find() && heading.start() <= definition.start) start = heading.start();
        String context = definition.corpus.text.substring(Math.max(start, definition.start - 3000), definition.start);
        return Pattern.compile("(?is)for\\s+the\\s+purposes?\\s+of\\s+(?:this|these)|in\\s+(?:this|these)\\s+[^.\\n]{0,240}(?:provisions|agreement|clause)")
                .matcher(context).find();
    }

    private boolean intentionalOverride(Definition a, Definition b) {
        Definition scc = "SCC".equalsIgnoreCase(a.corpus.source.fileKey) ? a
                : "SCC".equalsIgnoreCase(b.corpus.source.fileKey) ? b : null;
        Definition other = scc == a ? b : a;
        if (scc == null || !"GCC".equalsIgnoreCase(other.corpus.source.fileKey)) return false;
        Matcher matcher = GCC_TARGET.matcher(scc.corpus.text);
        while (matcher.find()) {
            String rest = scc.corpus.text.substring(matcher.end(), Math.min(scc.corpus.text.length(), matcher.end() + 150));
            if (rest.matches("(?is)^\\s+(?:is|are)\\s+amended\\s+by\\s+substituting.*")
                    && matcher.end() <= scc.start && scc.start < nextSection(scc.corpus.text, matcher.end())) return true;
        }
        return false;
    }

    private void personnelCounts(List<Corpus> corpora, List<Output> output, Set<String> seen) {
        Map<String, String> aliases = new HashMap<>();
        for (Corpus corpus : corpora) {
            Matcher role = ROLE_NAME.matcher(corpus.text);
            while (role.find()) if (role.group(2) != null) {
                String name = cleanRole(role.group(1));
                if (!validRole(name)) continue;
                String acronym = role.group(2).toUpperCase(Locale.ROOT), existing = aliases.get(acronym);
                if (existing == null || name.length() < existing.length()) aliases.put(acronym, name);
            }
        }
        Map<String, List<Requirement>> byRole = new LinkedHashMap<>();
        for (Corpus corpus : corpora) {
            Matcher before = COUNT_BEFORE.matcher(corpus.text);
            while (before.find()) {
                String prefix = corpus.text.substring(Math.max(0, before.start() - 80), before.start());
                // A numeric contents heading such as "2 CONTRACT MANAGER" is not staffing.
                boolean staffing = !before.group(2).matches("\\d+") || before.group(1) != null
                        || before.group().matches("(?is).*\\b(?:numbers?|persons?|staff)\\b.*")
                        || prefix.matches("(?is).*\\b(?:employ|provide|appoint|engage|retain|require)\\s+(?:a\\s+)?$");
                if (staffing) addRequirement(byRole, aliases, corpus, before.group(3), before.group(4),
                        before.group(2), before.group(1), before.start(), before.end());
            }
            Matcher after = COUNT_AFTER.matcher(corpus.text);
            while (after.find()) addRequirement(byRole, aliases, corpus, after.group(1), after.group(2),
                    after.group(4), after.group(3), after.start(), after.end());
            Matcher acronym = ACRONYM_AFTER.matcher(corpus.text);
            while (acronym.find()) if (aliases.containsKey(acronym.group(1))) addRequirement(byRole, aliases, corpus,
                    aliases.get(acronym.group(1)), acronym.group(1), acronym.group(3), acronym.group(2), acronym.start(), acronym.end());
        }
        for (List<Requirement> values : byRole.values()) for (int i = 0; i < values.size(); i++) {
            for (int j = i + 1; j < values.size(); j++) {
                Requirement a = values.get(i), b = values.get(j);
                if (a.count == b.count || (a.minimum && b.minimum)) continue;
                if ((a.minimum && b.count >= a.count) || (b.minimum && a.count >= b.count)) continue;
                if (a.corpus == b.corpus && a.start == b.start) continue;
                String key = "staff:" + a.role + ":" + a.corpus.source.id + ":" + a.start
                        + ":" + b.corpus.source.id + ":" + b.start;
                if (!seen.add(key)) continue;
                List<Evidence> evidence = a.corpus.evidence(a.start, a.end);
                evidence.addAll(b.corpus.evidence(b.start, b.end));
                output.add(new Output("personnel_requirement_coordination", "Personnel numbers require coordination",
                        "The explicit requirements for " + a.role + " differ: " + describe(a) + " versus " + describe(b)
                                + ". Their applicable work scope and period have not been established as identical, so this is a coordination risk rather than a confirmed contradiction. No number is inferred for provisions that do not state one.",
                        "Confirm whether these numbers apply to the same role, work scope and period; clarify or align the requirements if they do.",
                        "medium", "risk", unique(evidence)));
            }
        }
    }

    private void addRequirement(Map<String, List<Requirement>> byRole, Map<String, String> aliases, Corpus corpus,
                                String roleName, String acronym, String count, String qualifier, int start, int end) {
        String role = cleanRole(roleName);
        if (!validRole(role)) return;
        if (acronym != null) role = aliases.getOrDefault(acronym.toUpperCase(Locale.ROOT), role);
        if (!validRole(role)) return;
        boolean minimum = qualifier != null && !("total".equalsIgnoreCase(qualifier) || "exactly".equalsIgnoreCase(qualifier));
        byRole.computeIfAbsent(role, k -> new ArrayList<>()).add(new Requirement(role, number(count), minimum, corpus, start, end));
    }

    private String cleanRole(String role) {
        return normalize(role).replaceFirst("^(?:the words |the |number of |full time |full-time )", "");
    }

    private boolean validRole(String role) {
        return !role.matches("(?is).*\\b(years?|months?|days?|hours?|experience|qualification|degree|shall|must|may|will|submitted|submit|to|from|by|for)\\b.*");
    }

    private String describe(Requirement requirement) { return (requirement.minimum ? "minimum " : "stated ") + requirement.count; }

    private int number(String value) {
        String[] words = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
        for (int i = 0; i < words.length; i++) if (words[i].equalsIgnoreCase(value)) return i;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
    private static String canonical(String text) { return text.replaceAll("\\s+", "").toUpperCase(Locale.ROOT); }
    private List<String> numbers(String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) values.add(canonical(matcher.group()));
        return values;
    }
    private static List<Evidence> unique(List<Evidence> values) {
        Map<String, Evidence> result = new LinkedHashMap<>();
        for (Evidence value : values) result.put(value.documentId + ":" + value.blockId, value);
        return new ArrayList<>(result.values());
    }

    @Getter @AllArgsConstructor
    public static final class Source {
        private final String id;
        private final String fileKey;
        private final String fileName;
        private final String hash;
        private final List<DocumentBlock> blocks;
        private final String text;
    }
    @Getter @AllArgsConstructor
    public static final class Output {
        private final String ruleId;
        private final String title;
        private final String summary;
        private final String recommendation;
        private final String risk;
        private final String type;
        private final List<Evidence> evidence;
    }
    @Getter @AllArgsConstructor
    public static final class Evidence {
        private final String documentId;
        private final String fileKey;
        private final String fileName;
        private final String sourceHash;
        private final String blockId;
        private final String location;
        private final Integer pageNo;
        private final String anchor;
        private final String quote;
        private final double[] bbox;
    }
    @Getter @AllArgsConstructor
    public static final class ReviewResult {
        private final List<Output> outputs;
        private final int documentsScanned;
        private final int blocksScanned;
        private final int charactersScanned;
    }

    private static final class Unit {
        private final DocumentBlock block;
        private final int start, end;
        private final String anchor;
        private Unit(DocumentBlock block, int start, int end, String anchor) {
            this.block = block; this.start = start; this.end = end; this.anchor = anchor;
        }
    }
    private static final class Corpus {
        private final Source source;
        private final List<Unit> units = new ArrayList<>();
        private final String text;
        private Corpus(Source source) {
            this.source = source;
            List<DocumentBlock> blocks = source.blocks;
            if (blocks == null || blocks.isEmpty()) {
                blocks = new ArrayList<>();
                int offset = 0;
                for (String paragraph : (source.text == null ? "" : source.text).split("\\n", -1)) {
                    DocumentBlock block = new DocumentBlock();
                    block.setId("char:" + offset); block.setLocation("char/" + offset); block.setText(paragraph);
                    blocks.add(block); offset += paragraph.length() + 1;
                }
            }
            StringBuilder builder = new StringBuilder();
            String anchor = null;
            for (DocumentBlock block : blocks) {
                if (block == null || block.getText() == null) continue;
                Matcher heading = HEADING.matcher(block.getText());
                if (heading.find()) anchor = heading.group(1).trim();
                int start = builder.length();
                builder.append(block.getText());
                units.add(new Unit(block, start, builder.length(), anchor));
                builder.append('\n');
            }
            text = builder.toString();
        }
        private List<Evidence> evidence(int start, int end) {
            List<Evidence> result = new ArrayList<>();
            for (Unit unit : units) if (unit.end > start && unit.start < end && !unit.block.getText().trim().isEmpty()) {
                result.add(new Evidence(source.id, source.fileKey, source.fileName, source.hash, unit.block.getId(),
                        unit.block.getLocation(), unit.block.getPageNo(), unit.anchor, unit.block.getText(), unit.block.getBbox()));
            }
            return result;
        }
    }
    private static final class Deletion {
        private final String target;
        private final List<Evidence> evidence;
        private Deletion(String target, List<Evidence> evidence) { this.target = target; this.evidence = evidence; }
    }
    @AllArgsConstructor
    private static final class Definition {
        private final String term, meaning;
        private final Corpus corpus;
        private final int start, end;
    }
    @AllArgsConstructor
    private static final class Requirement {
        private final String role;
        private final int count;
        private final boolean minimum;
        private final Corpus corpus;
        private final int start, end;
    }
}
