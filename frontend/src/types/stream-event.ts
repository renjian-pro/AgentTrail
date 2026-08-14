export type TodoItem = { content: string; status: string }
/** 事件名和字段跟 ChatApplicationService#toEvent/payloadJson 一一对应，改动任一侧都要同步另一侧。 */
export type StreamEvent =
  | { type: 'RunStarted'; conversationId: string }
  | { type: 'ModelDelta'; content: string }
  | { type: 'ThinkingDelta'; content: string }
  | { type: 'ToolStarted'; toolName: string; toolCallId: string; arguments: string }
  | { type: 'ToolCompleted'; toolName: string; toolCallId: string; result: string }
  | { type: 'Paused'; conversationId: string; reason: string }
  | { type: 'RunFailed'; code: string; message: string }
  | { type: 'RunCompleted'; conversationId: string; turnId: number | null }
