# 内置 SDK

自定义音源只接在播放地址这一跳。曲库、歌词、封面由 `src/utils/musicSdk` 里的平台模块自己完成。这一版移动端把内置的播放地址实现注释掉了，`api-source-info.ts` 是空数组，所以在线歌曲的直链只来自当前选中的自定义脚本。

## 两层

```text
界面
  │
  ├─ musicSdk.wy / kw / kg / tx / mg     曲库：搜索、歌单、榜单、热搜、评论、歌词、封面
  │
  └─ musicSdk.*.getMusicUrl
        │
        └─ apis(平台)
              ├─ 设置是 user_api_…  →  脚本的 musicUrl，返回 https 直链
              └─ 否则查找内置 api   →  这一版列表是空的，调用会失败
```

`apis` 看的是设置项 `common.apiSource`。它以 `user_api` 开头时，返回脚本初始化时注册的那个平台对象。否则按「音源 id + 平台」去内置表里找，那张表在当前代码里没有条目。

虾米 `xm` 和百度 `bd` 的目录还在，但没有放进 `musicSdk.sources`，搜索和播放都不会走到它们。

## 五个在线平台各自提供

`kw` 酷我、`kg` 酷狗、`tx` QQ 音乐、`wy` 网易云、`mg` 咪咕，对外挂着同一组能力：

| 能力 | 作用 | 播放地址是否经过脚本 |
|---|---|---|
| `musicSearch.search(关键词, 页码, 条数)` | 搜歌 | 否 |
| `tipSearch` | 搜索建议。酷我有独立模块，其它平台不一定挂出 | 否 |
| `hotSearch` | 热搜 | 否 |
| `songList` | 歌单广场和歌单里的歌 | 否 |
| `leaderboard` | 榜单 | 否 |
| `comment` | 评论 | 否 |
| `getLyric` | 歌词 | 否。直接请求该平台 |
| `getPic` | 封面 | 否。直接请求该平台 |
| `getMusicUrl(歌曲, 音质)` | 播放地址 | 是。转到 `apis(平台)` |

酷狗文件里留着把 `getLyric`、`getPic` 也改走 `apis` 的注释，当前没有启用。

## 搜索返回

`search` 解析成功后返回：

| 字段 | 含义 |
|---|---|
| `list` | 歌曲数组 |
| `total` | 总数 |
| `limit` | 每页条数。网易云默认 30 |
| `allPage` | 总页数 |
| `source` | `kw` `kg` `tx` `wy` `mg` |

`list` 里的一首歌是曲目信息，还没有音频地址。网易云一条的字段是：`name`、`singer`、`albumName`、`albumId`、`source`、`interval`、`songmid`、`img`、`lrc`、`types`、`_types`、`typeUrl`。`types` 是这首歌有哪些音质，元素为 `{ type, size }`。`typeUrl` 此时是空对象。

失败时同一页最多再试 3 次。

## 从点击播放到出声

1. 界面上的歌来自搜索、歌单或榜单，来源平台已经写在 `source` 上。
2. 播放器按用户设置的音质，调用 `musicSdk[source].getMusicUrl(旧字段歌曲, 音质)`。
3. 这一步进入 `apis(source)`。自定义脚本被选中时，就是向脚本发 `musicUrl`，拿回 `{ type, url }`。
4. 地址会按「歌曲 + 音质」缓存。没有强制刷新时，下次直接用缓存。
5. 这一平台要地址失败，并且允许换源时：用歌名、歌手到其余四个平台再搜，按歌名、歌手、专辑、时长（允许差 5 秒）挑匹配结果，再对匹配到的那条歌要一次直链。请求过多的错误不再换源。

歌词和封面走各自平台的 `getLyric`、`getPic`，不读脚本。只有本地歌曲的地址、歌词、封面三件套都发给脚本，见 [动作与响应](./03-动作与响应.md)。

## 和自定义音源的分工

脚本初始化时声明的 `kw`、`kg` 等，表示「我能给这个平台的歌配直链」，不表示脚本接管了这个平台的搜索。用户在网易云页搜到的 `songmid`，播放时作为 `musicInfo.songmid` 交给脚本；脚本只用这个 id 去换一条 `https` 地址。
