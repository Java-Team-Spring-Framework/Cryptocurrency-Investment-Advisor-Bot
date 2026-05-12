package ru.spbstu.cryptoadvisor.service;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LLMPromptBuilder {

    public String buildAnalyzeCryptoInvestmentPrompt(String symbol, String fiat, Double currentPrice, String priceHistorySummary) {
        String priceInfo = currentPrice == null || currentPrice <= 0
                ? "Current price data not available."
                : String.format("Current price: %.2f %s.", currentPrice, fiat);

        return String.format(
                "Role: Expert crypto analyst.\n" +
                "Task: Analyze \"%s\" as a digital asset and provide a short investment recommendation.\n" +
                "%s\n" +
                "%s\n" +
                "RULE 1: If it is NOT a recognized cryptocurrency, reply EXACTLY: \"Sorry, it is not a cryptocurrency I can analyze.\"\n" +
                "RULE 2: If it IS a cryptocurrency, reply EXACTLY in this format (max 200 words total):\n" +
                "CONTEXT: [2 sentence]\n" +
                "RISKS: [2 sentence]\n" +
                "CATALYSTS: [2 sentence]\n" +
                "VERDICT: INVEST or DO NOT INVEST\n" +
                "REASON: [2 sentence]\n" +
                "Constraints: Zero extra text. No greetings. No disclaimers. Strict structure only. Base on current market conditions.",
                symbol,
                priceInfo,
                priceHistorySummary
        );
    }

    public String buildPortfolioReviewPrompt(String portfolioSummary, String portfolioPerformanceSummary, String priceHistorySummary) {
        return String.format(
                "Role: Expert crypto investment advisor.\n" +
                "Task: Review the following user portfolio and suggest improvements, including risk management and rebalancing ideas.\n" +
                "Portfolio:\n%s\n" +
                "Portfolio performance:\n%s\n" +
                "Recent price trends:\n%s\n" +
                "Important: Begin your answer by restating the current portfolio exactly as shown above, including symbol and amount, without price.\n" +
                "Then provide concise review and actionable recommendations.\n" +
                "Respond concisely with clear investment guidance and avoid any greeting or sign-off.\n" +
                "Constraints: Use current market context and give practical advice only.",
                portfolioSummary,
                portfolioPerformanceSummary,
                priceHistorySummary
        );
    }

    public String buildAskPrompt(String question, String portfolioSummary, String priceHistorySummary) {
        return String.format(
                "Role: Expert crypto investment advisor.\n" +
                "Task: Answer the user question considering the provided portfolio context and recent price history.\n" +
                "Question: %s\n" +
                "Portfolio context:\n%s\n" +
                "Recent price trends:\n%s\n" +
                "Provide a direct answer with relevant guidance, mention portfolio implications if applicable, and avoid greetings or marketing.\n" +
                "Important: Answer in the same language as the question.\n" +
                "Constraints: Keep the response concise and based on current market conditions.",
                question,
                portfolioSummary,
                priceHistorySummary
        );
    }
}
