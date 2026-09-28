# Step 1: Conversation memory

| ID | Type | Criterion |
| --- | --- | --- |
| S1-1 | CODE | `POST /api/ai/chat` accepts JSON `{conversationId, message}`. A missing `conversationId` gets a generated one, returned in the response. The old GET is removed or delegates to POST. |
| S1-2 | CODE | The tool-loop and general ChatClients have `MessageChatMemoryAdvisor` backed by a `ChatMemory` with a bounded window (about 20 messages). |
| S1-3 | CODE | Every call passes `ChatMemory.CONVERSATION_ID` via `.advisors(a -> a.param(...))`. There's no shared default conversation. |
| S1-4 | CODE | A `ResearchSession` (or equivalent) per conversation tracks the last resolved companies. When a message names no company, the router uses the session's company. |
| S1-5 | CODE | Test: after a turn about TCS, "what about its debt?" routes as SPECIFIC_QUESTION with company TCS. |
| S1-6 | CODE | Test: two conversation IDs don't share memory or session companies. |
| S1-7 | CODE | Verification isn't weakened. A figure the answer quotes from an earlier turn is accepted only if it's in the tool evidence stored for this conversation, and a figure from the model's own earlier text alone is not accepted. Test covers both cases. |
| S1-8 | CODE | The trace still logs each turn. The memory advisor's position relative to `ConversationTraceAdvisor` is deliberate and commented. |
| S1-L1 | LIVE | Script: `Research Infosys` → `how has it performed over 5 years?` → `and its debt?` with one conversationId. All three answers are about Infosys (INFY). |
