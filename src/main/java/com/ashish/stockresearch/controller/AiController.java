package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.service.AiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiController {

	private final AiService aiService;

	public AiController(AiService aiService) {
		this.aiService = aiService;
	}

	@GetMapping("/api/ai/chat")
	public String chat(@RequestParam String message) {
		return aiService.chat(message);
	}

}
