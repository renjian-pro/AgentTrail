# Ticket 21 execution evidence

Executed on 2026-08-12 against deterministic local replay inputs. No external model service was used.

| Framework | Entry point | Cases | Result | Timing |
|---|---|---:|---:|---:|
| AgentScope Java 2.0 | `AgentScopeRuntime` + `GoldenTaskRunner` | 3/3 | pass rate 1.0 | local OpenAI-compatible replay server |
| CrewAI 0.203.2 | real `Crew`/`Agent`/`Task` execution with a custom deterministic `BaseLLM` | 3/3 | pass rate 1.0 | 194–388 ms per task |
| AutoGPT official platform | official `ConcatenateListsBlock.execute` path | 3/3 | pass rate 1.0 | 1.08–4.59 ms per block |

The AgentScope proof is covered by `AgentScopeRuntimeProofTest`. CrewAI and AutoGPT were run in isolated Python 3.12 Docker environments because the host Python is 32-bit; the proof environments are disposable and are not production dependencies.
