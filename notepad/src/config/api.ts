/**
 * 后端 API 基址——全项目唯一来源（R2 部署改造）。
 *
 * dev 与生产统一走相对路径 '/api'：
 * - 开发：vite devServer 代理 /api → http://localhost:8090（.env.development）
 * - 生产：nginx 反代 /api/ → 127.0.0.1:8090（后端端口不公网暴露）
 */
export const API_BASE = '/api';
