package org.neo4j.shell.prettyprint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.summary.Plan;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static org.neo4j.shell.prettyprint.OutputFormatter.NEWLINE;
import static org.neo4j.shell.prettyprint.OutputFormatter.repeat;

public class TablePlanFormatter {

    private static final String UNNAMED_PATTERN_STRING = "  (UNNAMED|FRESHID|AGGREGATION|NODE|REL)(\\d+)";
    private static final Pattern UNNAMED_PATTERN = Pattern.compile(UNNAMED_PATTERN_STRING);
    private static final String OPERATOR = "Operator";
    private static final String ESTIMATED_ROWS = "Estimated Rows";
    private static final String ROWS = "Rows";
    private static final String HITS = "DB Hits";
    private static final String PAGE_CACHE = "Cache H/M";
    private static final String TIME = "Time (ms)";
    private static final String ORDER = "Ordered by";
    private static final String MEMORY = "Memory (Bytes)";
    public static final String IDENTIFIERS = "Identifiers";
    private static final String OTHER = "Other";
    public static final String DETAILS = "Details";
    private static final String SEPARATOR = ", ";
    private static final Pattern DEDUP_PATTERN = Pattern.compile("\\s*(\\S+)@\\d+");
    public static final int MAX_DETAILS_COLUMN_WIDTH = 100;

    private static final List<String> HEADERS = asList(OPERATOR, DETAILS, ESTIMATED_ROWS, ROWS, HITS, PAGE_CACHE, TIME, MEMORY, IDENTIFIERS, ORDER, OTHER);

    private static final Set<String> IGNORED_ARGUMENTS = new LinkedHashSet<>(
            asList( "Rows", "DbHits", "EstimatedRows", "planner", "planner-impl", "planner-version", "version", "runtime", "runtime-impl", "runtime-version",
                    "time", "source-code", "PageCacheMisses", "PageCacheHits", "PageCacheHitRatio", "Order", "Memory", "GlobalMemory", "Details" ) );
    public static final Value ZERO_VALUE = Values.value(0);

    private int width(@Nonnull String header, @Nonnull Map<String, Integer> columns) {
        return 2 + Math.max(header.length(), columns.get(header));
    }

    private void pad(int width, char chr, @Nonnull StringBuilder result) {
        result.append(OutputFormatter.repeat(chr, width));
    }


    private void divider(@Nonnull List<String> headers, @Nullable Line line /*= null*/, @Nonnull StringBuilder result, @Nonnull Map<String, Integer> columns) {
        for (String header : headers) {
            if (line != null && header.equals(OPERATOR) && line.connection.isPresent()) {
                result.append("|");
                String connection = line.connection.get();
                result.append(" ").append(connection);
                pad(width(header, columns) - connection.length() - 1, ' ', result);
            } else {
                result.append("+");
                pad(width(header, columns), '-', result);
            }
        }
        result.append("+").append(NEWLINE);
    }

    @Nonnull
    String formatPlan(@Nonnull Plan plan) {
        Map<String, Integer> columns = new HashMap<>();
        List<Line> lines = accumulate(plan, new Root(), columns);

        // Remove Identifiers column if we have a Details column
        List<String> headers = HEADERS.stream().filter(header -> columns.containsKey(header) && !(header.equals(IDENTIFIERS) && columns.containsKey(DETAILS))).collect(Collectors.toList());

        StringBuilder result = new StringBuilder((2 + NEWLINE.length() + headers.stream().mapToInt(h -> width(h, columns)).sum()) * (lines.size() * 2 + 3));

        List<Line> allLines = new ArrayList<>();
        Map<String, List<Justified>> headerMap = headers.stream().map(header -> Pair.of(header, Collections.singletonList((Justified)new Left(header)))).collect(toMap(p -> p._1, p -> p._2));
        allLines.add(new Line(OPERATOR, headerMap, Optional.empty()));
        allLines.addAll(lines);
        for (int lineIndex = 0; lineIndex < allLines.size(); lineIndex++) {
            Line line = allLines.get(lineIndex);
            divider(headers, line, result, columns);
            int lineHeight = line.details.values().stream().max(Comparator.comparingInt( v -> v.size() )).get().size();
            for(int i = 0; i< lineHeight; i++) {
                for (String header : headers) {
                    List<Justified> details = line.get(header);
                    Justified detail = new Left("");
                    if (details.size() > i) {
                        detail = details.get(i);
                    } else if (OPERATOR == header && lineIndex < allLines.size() - 1) {
                        detail = new Left(allLines.get(lineIndex + 1).connection.orElse( "" ).replace( '\\', ' ' ));
                    }
                    result.append( "| " );
                    if ( detail instanceof Left )
                    {
                        result.append( detail.text );
                        pad( width( header, columns ) - detail.length - 2, ' ', result );
                    }
                    if ( detail instanceof Right )
                    {
                        pad( width( header, columns ) - detail.length - 2, ' ', result );
                        result.append( detail.text );
                    }
                    result.append( " " );
                }
                result.append("|").append(NEWLINE);
            }
        }
        divider(headers, null, result, columns);

        return result.toString();
    }

    @Nonnull
    private String serialize(@Nonnull String key, @Nonnull Value v) {
        switch (key) {
            case "ColumnsLeft":
                return removeGeneratedNames(v.asString());
            case "LegacyExpression":
                return removeGeneratedNames(v.asString());
            case "Expression":
                return removeGeneratedNames(v.asString());
            case "UpdateActionName":
                return v.asString();
            case "LegacyIndex":
                return v.toString();
            case "version":
                return v.toString();
            case "planner":
                return v.toString();
            case "planner-impl":
                return v.toString();
            case "runtime":
                return v.toString();
            case "runtime-impl":
                return v.toString();
            case "MergePattern":
                return "MergePattern(" + v.toString() + ")";
            case "DbHits":
                return v.asNumber().toString();
            case "Rows":
                return v.asNumber().toString();
            case "Time":
                return v.asNumber().toString();
            case "EstimatedRows":
                return v.asNumber().toString();
            case "LabelName":
                return v.asString();
            case "KeyNames":
                return removeGeneratedNames(v.asString());
            case "KeyExpressions":
                return String.join(SEPARATOR, v.asList(Value::asString));

            case "ExpandExpression":
                return removeGeneratedNames(v.asString());
            case "Index":
                return v.asString();
            case "PrefixIndex":
                return v.asString();
            case "InequalityIndex":
                return v.asString();
            case "EntityByIdRhs":
                return v.asString();
            case "PageCacheMisses":
                return v.asNumber().toString();
            case "Details":
                return v.asString();
            default:
                return v.asObject().toString();
        }
    }

    @Nonnull private Stream<List<Line>> children(@Nonnull Plan plan, Level level,@Nonnull Map<String, Integer> columns) {
        List<? extends Plan> c = plan.children();
        switch (c.size()) {
            case 0:
                return Stream.empty();
            case 1:
                return Stream.of(accumulate(c.get(0), level.child(), columns));
            case 2:
                return Stream.of(accumulate(c.get(1), level.fork(), columns), accumulate(c.get(0), level.child(), columns));
        }
        throw new IllegalStateException("Plan has more than 2 children " + c);
    }

    @Nonnull private List<Line> accumulate(@Nonnull Plan plan, @Nonnull Level level, @Nonnull Map<String, Integer> columns) {
        String line = level.line() + plan.operatorType(); // wa plan.name
        mapping(OPERATOR, new Left(line), columns);

        return Stream.concat(
                Stream.of(new Line(line, details(plan, columns), level.connector())),
                children(plan, level, columns).flatMap(Collection::stream))
                .collect(Collectors.toList());
    }

    @Nonnull
    private Map<String, List<Justified>> details(@Nonnull Plan plan, @Nonnull Map<String, Integer> columns) {
        Map<String, Value> args = plan.arguments();

        Stream<Optional<Pair<String, List<Justified>>>> formattedPlan = args.entrySet().stream().map((e) -> {
            Value value = e.getValue();
            switch (e.getKey()) {
                case "EstimatedRows":
                    return mapping(ESTIMATED_ROWS, new Right(format(value.asDouble())), columns);
                case "Rows":
                    return mapping(ROWS, new Right(value.asNumber().toString()), columns);
                case "DbHits":
                    return mapping(HITS, new Right(value.asNumber().toString()), columns);
                case "PageCacheHits":
                    return mapping(PAGE_CACHE, new Right(String.format("%s/%s",value.asNumber(),args.getOrDefault("PageCacheMisses", ZERO_VALUE).asNumber())), columns);
                case "Time":
                    return mapping(TIME, new Right(String.format("%.3f", value.asLong() / 1000000.0d)), columns);
                case "Order":
                    return mapping( ORDER, new Left( String.format( "%s", value.asString() ) ), columns );
                case "Details":
                    return mapping( DETAILS, multiLineDetails(value.asString()), columns );
                case "Memory":
                    return mapping( MEMORY, new Right( String.format( "%s", value.asNumber().toString() ) ), columns );
                default:
                    return Optional.empty();
            }
        });

        List<Justified> identifiers = Collections.singletonList( new Left( identifiers( plan, columns ) ) );
        List<Justified> others = Collections.singletonList( new Left( other( plan, columns ) ) );
        return Stream.concat(
                formattedPlan,
                Stream.of(
                        Optional.of(Pair.of( IDENTIFIERS, identifiers )),
                        Optional.of(Pair.of( OTHER, others ))))
                     .filter(Optional::isPresent)
                     .collect(toMap(o -> o.get()._1, o -> o.get()._2));
    }

    @Nonnull
    private Optional<Pair<String, List<Justified>>> mapping(@Nonnull String key, @Nonnull Justified value, @Nonnull Map<String, Integer> columns) {
        update(columns, key, value.length);
        return Optional.of(Pair.of(key, Collections.singletonList(value)));
    }

    @Nonnull
    private Optional<Pair<String, List<Justified>>> mapping(@Nonnull String key, @Nonnull List<Justified> values, @Nonnull Map<String, Integer> columns) {
        Integer maxLength = values.stream().max( Comparator.comparingInt( val -> val.length ) ).map( val -> val.length).orElse( 0 );
        update(columns, key, maxLength);
        return Optional.of(Pair.of(key, values));
    }

    @Nonnull
    private String replaceAllIn(@Nonnull Pattern pattern, @Nonnull String s, @Nonnull Function<Matcher, String> mapper) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = pattern.matcher(s);
        while (matcher.find()) {
            matcher.appendReplacement(sb, mapper.apply(matcher));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @Nonnull
    private String removeGeneratedNames(@Nonnull String s) {
        String named = replaceAllIn(UNNAMED_PATTERN, s, m -> "anon[" + m.group(2) + "]");
        return replaceAllIn(DEDUP_PATTERN, named, m -> m.group(1));
    }

    private void update(@Nonnull Map<String, Integer> columns, @Nonnull String key, int length) {
        columns.put(key, Math.max(columns.getOrDefault(key, 0), length));
    }

    @Nonnull
    private String identifiers(@Nonnull Plan description, @Nonnull Map<String, Integer> columns) {
        String result = description.identifiers().stream().map(this::removeGeneratedNames).collect(joining(", "));
        if (!result.isEmpty()) {
            update(columns, IDENTIFIERS, result.length());
        }
        return result;
    }

    @Nonnull
    private String other(@Nonnull Plan description, @Nonnull Map<String, Integer> columns) {
        String result = description.arguments().entrySet().stream().map(e -> {
            if (!IGNORED_ARGUMENTS.contains(e.getKey())) return serialize(e.getKey(), e.getValue());
            return "";
        }).filter(OutputFormatter::isNotBlank).collect(Collectors.joining("; ")).replaceAll(UNNAMED_PATTERN_STRING, "");

        if (!result.isEmpty()) {
            update(columns, OTHER, result.length());
        }
        return result;
    }

    @Nonnull
    private String format(@Nonnull Double v) {
        if (v.isNaN()) return v.toString();
        return String.valueOf(Math.round(v));
    }


    static class Line {

        private final String tree;
        private final Map<String, List<Justified>> details;
        private final Optional<String> connection;

        Line(String tree, Map<String, List<Justified>> details, Optional<String> connection) {
            this.tree = tree;
            this.details = details;
            this.connection = connection == null ? Optional.empty() : connection;
        }

        List<Justified> get(String key) {
            if (key.equals(TablePlanFormatter.OPERATOR)) {
                return Collections.singletonList(new Left(tree));
            } else
                return details.getOrDefault(key, Collections.singletonList(new Left("")));
        }
    }

    static abstract class Justified {
        final int length;
        final String text;

        Justified(String text) {
            this.length = text.length();
            this.text = text;
        }
    }

    static class Left extends Justified {
        Left(String text) {
            super(text);
        }
    }

    static class Right extends Justified {
        Right(String text) {
            super(text);
        }
    }

    static abstract class Level {
        abstract Level child();

        abstract Level fork();

        abstract String line();

        abstract Optional<String> connector();
    }

    static class Root extends Level {
        @Override
        Level child() {
            return new Child(1);
        }

        @Override
        Level fork() {
            return new Fork(2);
        }

        @Override
        String line() {
            return "+";
        }

        @Override
        Optional<String> connector() {
            return Optional.empty();
        }

    }

    static class Child extends Level {
        private final int level;

        Child(int level) {

            this.level = level;
        }

        @Override
        Level child() {
            return new Child(level);
        }

        @Override
        Level fork() {
            return new Fork(level + 1);
        }

        @Override
        String line() {
            return repeat("| ", (level - 1)) + "+";
        }

        @Override
        Optional<String> connector() {
            return Optional.of(repeat("| ", level));
        }
    }

    static class Fork extends Level {
        private final int level;

        Fork(int level) {

            this.level = level;
        }

        @Override
        Level child() {
            return new Child(level);
        }

        @Override
        Level fork() {
            return new Fork(level + 1);
        }

        @Override
        String line() {
            return repeat("| ", level - 1) + "+";
        }

        @Override
        Optional<String> connector() {
            return Optional.of(repeat("| ", level - 2) + "|\\");
        }
    }

    static final class Pair<T1, T2> {
        final T1 _1;
        final T2 _2;

        private Pair(T1 _1, T2 _2) {
            this._1 = _1;
            this._2 = _2;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Pair<?, ?> pair = (Pair<?, ?>) o;
            return _1.equals(pair._1) && _2.equals(pair._2);
        }

        @Override
        public int hashCode() {
            return 31 * _1.hashCode() + _2.hashCode();
        }

        public static <T1, T2> Pair<T1, T2> of(T1 _1, T2 _2) {
            return new Pair<>(_1, _2);
        }
    }

    private List<Justified> multiLineDetails( String original ) {
        List<Justified> detailsList = new ArrayList<Justified>();

        int currentPos = 0;
        while(currentPos < original.length()){
            int newPos = Math.min(original.length(), currentPos + MAX_DETAILS_COLUMN_WIDTH);
            detailsList.add(new Left(original.substring( currentPos,  newPos)));
            currentPos = newPos;
        }

        return detailsList;
    }
}
