# 自定义音源文档索引

来源：[lx-music-mobile](https://github.com/lyswhut/lx-music-mobile) `fb84807`。说明见 [README](./README.md)。

| # | 标题 | 文档 |
|---|---|---|
| 1 | 链接导入 | [打开](./01-链接导入.md) |
| 2 | 脚本生命周期 | [打开](./02-脚本生命周期.md) |
| 3 | 动作与响应 | [打开](./03-动作与响应.md) |
| 4 | 歌曲对象 | [打开](./04-歌曲对象.md) |
| 5 | 脚本内请求 | [打开](./05-脚本内请求.md) |
| 6 | 内置 SDK | [打开](./06-内置SDK.md) |

应用向脚本发出的动作只有三个：

| 动作 | 谁会收到 | 脚本必须返回 |
|---|---|---|
| `musicUrl` | `kw` `kg` `tx` `wy` `mg` `local` | `http` 或 `https` 字符串 |
| `lyric` | 仅 `local` | `{ lyric, tlyric?, rlyric?, lxlyric? }` |
| `pic` | 仅 `local` | `http` 或 `https` 字符串 |
