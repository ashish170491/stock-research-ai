# Stock Research AI

## Prerequisites

- Java 21
- Maven (or use the included `./mvnw`)
- [Ollama](https://ollama.com) installed
- `qwen3:8b` model pulled (`ollama pull qwen3:8b`)

## How to start Ollama

```bash
ollama serve
```

(If you normally run `ollama run qwen3:8b` in a terminal, that already starts the server on `http://localhost:11434`.)

## How to start Spring Boot

```bash
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080`. The model name is configurable via `spring.ai.ollama.chat.model` in `src/main/resources/application.yml`.

## How to test the endpoint

```bash
curl "http://localhost:8080/api/ai/chat?message=Explain%20Spring%20AI%20in%20two%20sentences"
```

If Ollama isn't running, the endpoint returns HTTP 503 with an explanatory message instead of a stack trace.
