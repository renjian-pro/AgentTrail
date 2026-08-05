/**
 * 从一次拖放里解析出要上传的文件。
 *
 * 拖一个文件夹进来，浏览器不会拒绝——dataTransfer.files 里照样会落一个零字节的 File，
 * 直接照单全收的话会绕过前端校验，最后在服务端才被拒成一句干巴巴的 "Unprocessable
 * Content"，对刚拖进来的人没有任何帮助。DataTransferItem.webkitGetAsEntry() 是唯一能在
 * 那之前看出"这是个目录"的办法，所以这里先查一遍 entries，是目录就直接拒绝，不构造任何
 * 上传请求。
 */
export type FileDropResult =
  | { kind: 'file'; file: File }
  | { kind: 'rejected'; reason: string }
  | { kind: 'none' }

export function resolveDroppedFile(event: DragEvent): FileDropResult {
  const items = event.dataTransfer?.items
  if (items) {
    for (const item of items) {
      const entry = item.webkitGetAsEntry?.()
      if (entry?.isDirectory) return { kind: 'rejected', reason: '不支持上传文件夹，请选择单个文件' }
    }
  }
  const file = event.dataTransfer?.files?.[0]
  return file ? { kind: 'file', file } : { kind: 'none' }
}
