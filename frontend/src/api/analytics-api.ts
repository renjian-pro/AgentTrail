import { request } from './http'

export type AnalyticsField = {
  name: string
  type: string
  comment: string
  primaryKey: boolean
  examples: string[]
}
export type AnalyticsTable = {
  name: string
  comment: string
  fields: AnalyticsField[]
  foreignKeys: { fromColumn: string; toTable: string; toColumn: string }[]
}
export type AnalyticsSchema = { database: string; tables: AnalyticsTable[] }
export type GlossaryEntry = {
  term: string
  synonyms: string[]
  description: string
  sqlFragment: string
  example: string
}

export const analyticsApi = {
  schema: () => request<AnalyticsSchema>('/api/analytics/schema'),
  glossary: () => request<GlossaryEntry[]>('/api/analytics/glossary')
}
