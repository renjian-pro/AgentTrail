export type TodoItem = { content: string; status: string }
export type StreamEvent =
  | { type: 'AgentStart'; conversationId: string }
  | { type: 'Thinking'; content: string }
  | { type: 'Text'; content: string }
  | { type: 'ToolStart'; toolName: string; toolCallId: string; arguments: string }
  | { type: 'ToolEnd'; toolName: string; toolCallId: string; result: string }
  | { type: 'TodoProgress'; items: TodoItem[] }
  | { type: 'StageOutput'; stage: string; data: unknown }
  | { type: 'Error'; code: string; message: string }
  | { type: 'Complete'; conversationId: string; turnId: number | null }
