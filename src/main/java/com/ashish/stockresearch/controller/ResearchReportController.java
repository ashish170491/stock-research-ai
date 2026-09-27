package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.StockResearchReport;
import com.ashish.stockresearch.service.AiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResearchReportController {

	private final AiService aiService;
	private final ResearchReportService researchReportService;

	public ResearchReportController(AiService aiService, ResearchReportService researchReportService) {
		this.aiService = aiService;
		this.researchReportService = researchReportService;
	}

	/** Markdown report: Java-rendered facts, observations, gaps and sources, plus a model-written interpretation. */
	@GetMapping(value = "/api/research/report", produces = "text/markdown;charset=UTF-8")
	public String report(@RequestParam String symbol) {
		return aiService.researchReport(symbol);
	}

	/** The structured report behind the Markdown, with no model involvement at all. */
	@GetMapping("/api/research/report/data")
	public StockResearchReport reportData(@RequestParam String symbol) {
		return researchReportService.build(symbol);
	}

}
