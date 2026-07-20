# SaicoPvP HeadHunting Remake

這是一個為 Paper 1.21.11 獨立實作、偏向正式環境使用的 SaicoPvP HeadHunting 重製外掛。它提供 25 級進度、
可交易且可驗證的生物頭顱、玩家懸賞頭顱、Souls、Head Exchange、多語系 GUI，以及可在伺服器中斷後復原的
SQLite 交易。這是非官方社群專案，與 SaicoPvP 沒有隸屬或背書關係。

## 安裝需求

- Paper 1.21.11
- Java 21 或更新版本
- 不需要其他執行期相依外掛

把 GitHub Release 的 `SaicoPvP-Headhunting-Remake-1.0.0.jar` 放進 `plugins`，啟動一次產生設定檔後關服，
檢查 `config.yml`、`heads.yml`、`levels.yml` 與 `exchanges.yml`，再重新開服。之後修改設定請使用
`/headhunt admin reload`，不要使用 Bukkit `/reload`。

備份時必須一起保留 `headhunting.db` 與 `secret.key`。若遺失密鑰，舊頭顱將無法再驗證；為避免意外破壞既有
經濟資料，外掛在發現舊資料庫但沒有密鑰時會拒絕產生新密鑰。

## 核心操作

- `/headhunt` 或 `/level`：開啟等級 GUI
- `/rankup`：完成符合條件的目前等級
- `/headhunt sell [hand|all]`：出售手中或背包內可用頭顱
- `/headhunt exchange`：開啟 Head Exchange
- `/headhunt status`：查看餘額、Souls 與進度
- `/headhunt language <locale|auto>`：選擇並保存語系，或跟隨客戶端

預設 `SELL_HEADS` 模式重現出售目前等級頭顱以累積進度的玩法；`DIRECT_KILLS` 則可切換為擊殺時直接累積
進度。數值是受歷史玩法啟發的可調整預設，並不宣稱對應某一季的完整原始設定。

完整指令、權限、設定、備份與故障復原請參閱[管理員指南](docs/ADMIN_GUIDE.md)；設計依據請參閱
[研究紀錄](docs/RESEARCH.md)，測試證據請參閱[測試文件](docs/TESTING.md)。英文主文件包含安全性、整合邊界
與開發 API 的完整說明。
