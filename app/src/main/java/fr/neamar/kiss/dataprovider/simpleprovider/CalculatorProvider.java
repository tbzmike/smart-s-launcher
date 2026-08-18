package fr.neamar.kiss.dataprovider.simpleprovider;

import androidx.annotation.VisibleForTesting;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.neamar.kiss.pojo.SearchPojo;
import fr.neamar.kiss.pojo.SearchPojoType;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.calculator.Calculator;
import fr.neamar.kiss.utils.calculator.Result;
import fr.neamar.kiss.utils.calculator.ShuntingYard;
import fr.neamar.kiss.utils.calculator.Tokenizer;

public class CalculatorProvider extends SimpleProvider<SearchPojo> {
    @VisibleForTesting() final Pattern computableRegexp;
    private final Pattern numberOnlyRegexp;
    private final Pattern trailingPercentRegexp;
    private final Pattern unitRegexp;
    private final Pattern dateMathRegexp;
    private final NumberFormat LOCALIZED_NUMBER_FORMATTER = NumberFormat.getInstance();
    private static final BigDecimal HUNDRED = new BigDecimal(100);
    private static final Map<String, Unit> UNITS = new HashMap<>();

    private static final class Unit {
        final String dimension;
        final double toBase;
        Unit(String dimension, double toBase) { this.dimension = dimension; this.toBase = toBase; }
    }

    static {
        addUnit("m", "length", 1); addUnit("meter", "length", 1); addUnit("meters", "length", 1);
        addUnit("km", "length", 1000); addUnit("cm", "length", 0.01); addUnit("mm", "length", 0.001);
        addUnit("mi", "length", 1609.344); addUnit("mile", "length", 1609.344); addUnit("miles", "length", 1609.344);
        addUnit("ft", "length", 0.3048); addUnit("feet", "length", 0.3048); addUnit("in", "length", 0.0254);
        addUnit("kg", "mass", 1); addUnit("g", "mass", 0.001); addUnit("lb", "mass", 0.45359237); addUnit("lbs", "mass", 0.45359237);
        addUnit("l", "volume", 1); addUnit("liter", "volume", 1); addUnit("liters", "volume", 1); addUnit("ml", "volume", 0.001);
    }

    private static void addUnit(String name, String dimension, double toBase) { UNITS.put(name, new Unit(dimension, toBase)); }

    public CalculatorProvider() {
        computableRegexp = Pattern.compile("^[\\-.,\\d+*×x/÷^'()%]+$");
        numberOnlyRegexp = Pattern.compile("^\\+?[.,()\\d]+$");
        trailingPercentRegexp = Pattern.compile("^([^%]+?)([+\\-])(\\d+(?:[.,]\\d+)?)%$");
        unitRegexp = Pattern.compile("(?i)^\\s*(-?\\d+(?:[.,]\\d+)?)\\s*([a-z]+)\\s+(?:in|to)\\s+([a-z]+)\\s*$");
        dateMathRegexp = Pattern.compile("(?i)^\\s*(today|\\d{4}-\\d{2}-\\d{2})\\s*([+-])\\s*(\\d+)\\s*(day|days|week|weeks|month|months|year|years)\\s*$");
    }

    @Override
    public void requestResults(String query, Searcher searcher) {
        String smart = computeSmart(query);
        if (smart != null) {
            addCalculatorResult(query + " = " + smart, 80, searcher);
            return;
        }

        String spacelessQuery = query.replaceAll("\\s+", "");
        Matcher m = computableRegexp.matcher(spacelessQuery);
        if (!m.find() || numberOnlyRegexp.matcher(spacelessQuery).find()) return;
        BigDecimal value = compute(m.group());
        if (value != null) addCalculatorResult(m.group() + " = " + LOCALIZED_NUMBER_FORMATTER.format(value), 19, searcher);
    }

    private void addCalculatorResult(String text, int relevance, Searcher searcher) {
        SearchPojo pojo = new SearchPojo("calculator://" + text.hashCode(), text, "", SearchPojoType.CALCULATOR_QUERY);
        pojo.relevance = relevance;
        searcher.addResult(pojo);
    }

    private String computeSmart(String query) {
        Matcher unit = unitRegexp.matcher(query);
        if (unit.matches()) {
            double value = Double.parseDouble(unit.group(1).replace(',', '.'));
            String fromName = unit.group(2).toLowerCase(Locale.ROOT);
            String toName = unit.group(3).toLowerCase(Locale.ROOT);
            Unit from = UNITS.get(fromName); Unit to = UNITS.get(toName);
            if (from != null && to != null && from.dimension.equals(to.dimension)) {
                return LOCALIZED_NUMBER_FORMATTER.format(value * from.toBase / to.toBase) + " " + toName;
            }
        }

        Matcher date = dateMathRegexp.matcher(query);
        if (date.matches()) {
            try {
                LocalDate base = date.group(1).equalsIgnoreCase("today") ? LocalDate.now() : LocalDate.parse(date.group(1));
                int amount = Integer.parseInt(date.group(3));
                if (date.group(2).equals("-")) amount = -amount;
                String unitName = date.group(4).toLowerCase(Locale.ROOT);
                if (unitName.startsWith("day")) base = base.plusDays(amount);
                else if (unitName.startsWith("week")) base = base.plusWeeks(amount);
                else if (unitName.startsWith("month")) base = base.plusMonths(amount);
                else if (unitName.startsWith("year")) base = base.plusYears(amount);
                return base.toString();
            } catch (DateTimeParseException | NumberFormatException ignored) { return null; }
        }
        return null;
    }

    @VisibleForTesting
    BigDecimal compute(String spacelessQuery) {
        Matcher percentMatcher = trailingPercentRegexp.matcher(spacelessQuery);
        if (percentMatcher.matches()) {
            BigDecimal base = evaluate(percentMatcher.group(1));
            if (base == null) return null;
            BigDecimal percent = new BigDecimal(percentMatcher.group(3).replace(",", "."));
            BigDecimal delta = base.multiply(percent).divide(HUNDRED, MathContext.DECIMAL32);
            return percentMatcher.group(2).equals("+") ? base.add(delta) : base.subtract(delta);
        }
        return evaluate(spacelessQuery);
    }

    private static BigDecimal evaluate(String expression) {
        Result<ArrayDeque<Tokenizer.Token>> tokenized = Tokenizer.tokenize(expression);
        if (tokenized.syntacticalError || tokenized.arithmeticalError) return null;
        Result<ArrayDeque<Tokenizer.Token>> postfixed = ShuntingYard.infixToPostfix(tokenized.result);
        if (postfixed.syntacticalError || postfixed.arithmeticalError) return null;
        Result<BigDecimal> result = Calculator.calculateExpression(postfixed.result);
        if (result.syntacticalError || result.arithmeticalError) return null;
        return result.result;
    }
}
