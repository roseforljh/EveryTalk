/** 与安卓 AiContentReportCategory 相同的枚举和中文名称；浏览器与后端 /v1/report 共用，保持无依赖。 */
export const reportCategories = [
  ['CHILD_SAFETY', '儿童安全'], ['SEXUAL_CONTENT', '色情或私密内容'], ['VIOLENCE_SELF_HARM', '暴力或自伤'],
  ['HATE_HARASSMENT', '仇恨或骚扰'], ['DECEPTION_IMPERSONATION', '欺诈或冒充'], ['MALICIOUS_CODE', '恶意代码'], ['OTHER', '其他问题'],
] as const
