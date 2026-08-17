// 跨模块共用的常量。放这儿是为了断开依赖环：
// 若让 poster.js 回头从 app.js 取 WEEK，就成了 app → poster → app。

export const WEEK = ['日', '一', '二', '三', '四', '五', '六'];
