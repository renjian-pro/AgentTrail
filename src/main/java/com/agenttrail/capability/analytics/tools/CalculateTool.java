package com.agenttrail.capability.analytics.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.function.Function;
import com.agenttrail.loop.tools.JsonToolCallback;
import com.agenttrail.loop.tools.ToolArguments;
import org.springframework.ai.tool.ToolCallback;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** 仅执行最终数学公式，批量聚合必须交给 SQL；表达式引擎不具备 IO 或系统调用能力。 */
public final class CalculateTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Function ROUND = new Function("round", 2) {
        @Override
        public double apply(double... args) {
            return BigDecimal.valueOf(args[0])
                    .setScale((int) args[1], RoundingMode.HALF_UP)
                    .doubleValue();
        }
    };

    private CalculateTool() { }

    public static ToolCallback callback() {
        return new JsonToolCallback(
                "calculate",
                "计算最终数学公式，例如环比、占比和增长率；批量聚合请先用 SQL。",
                "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"},\"variablesJson\":{\"type\":\"string\"}},\"required\":[\"expression\",\"variablesJson\"]}",
                CalculateTool::calculate);
    }

    private static String calculate(ToolArguments arguments) {
        String expressionText = arguments.text("expression");
        String variablesJson = arguments.text("variablesJson");
        if (expressionText == null || expressionText.isBlank()) {
            return "Error: expression 不能为空";
        }
        if (variablesJson == null || variablesJson.isBlank()) {
            return "Error: variablesJson 不能为空";
        }
        try {
            Map<String, Double> variables = parseVariables(variablesJson);
            Expression expression = new ExpressionBuilder(expressionText)
                    .variables(variables.keySet())
                    .function(ROUND)
                    .build()
                    .setVariables(variables);
            net.objecthunter.exp4j.ValidationResult validation = expression.validate();
            if (!validation.isValid()) {
                return "Error: 表达式无效：" + String.join("；", validation.getErrors());
            }
            return format(expression.evaluate());
        } catch (Exception failure) {
            return "Error: 表达式计算失败：" + failure.getMessage();
        }
    }

    private static Map<String, Double> parseVariables(String json) throws Exception {
        JsonNode root = JSON.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("variablesJson 必须是 JSON 对象");
        }
        Map<String, Double> values = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isNumber()) {
                throw new IllegalArgumentException("变量 " + field.getKey() + " 必须是数字");
            }
            values.put(field.getKey(), field.getValue().doubleValue());
        }
        return values;
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) {
            return "Error: 计算结果不是有限数字";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
