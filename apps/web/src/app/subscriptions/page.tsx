import { WorkspacePage } from "@/components/workspace-page";

export default function SubscriptionsPage() {
  return <WorkspacePage title="订阅与邮件" description="每日、每周简报与投递状态；默认 Asia/Shanghai。" actionLabel="新建订阅" metrics={[["2", "启用订阅"], ["09:00", "每日发送"], ["周一", "每周简报"], ["100%", "近 7 日送达"]]} cards={[{ title: "每日 AI 精选", description: "每天 09:00，全部主题，最多 12 条。" }, { title: "Agent 与 AI 编码周报", description: "每周一 09:00，两个主题，最多 20 条。" }, { title: "偏好与退订", description: "每封邮件提供偏好入口和一次性退订链接。" }]} />;
}
