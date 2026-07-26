import type { IconName } from "@/components/icons";

export type NavigationItem = {
  href: string;
  label: string;
  icon: IconName;
  access?: "authenticated" | "operator" | "admin";
};

export type NavigationGroup = {
  label: string;
  items: NavigationItem[];
};

export const navigation: NavigationGroup[] = [
  {
    label: "内容",
    items: [
      { href: "/", label: "精选", icon: "spark" },
      { href: "/all", label: "全部 AI 动态", icon: "stream" },
      { href: "/reports", label: "AI 日报", icon: "report" },
      { href: "/topics", label: "主题", icon: "topics" },
      { href: "/favorites", label: "收藏", icon: "bookmark" },
    ],
  },
  {
    label: "智能",
    items: [
      { href: "/subscriptions", label: "订阅与邮件", icon: "mail", access: "authenticated" },
      { href: "/research", label: "知识库问答", icon: "research", access: "authenticated" },
      { href: "/settings/models", label: "我的模型", icon: "settings", access: "authenticated" },
      { href: "/agent", label: "Agent 工作台", icon: "agent", access: "authenticated" },
    ],
  },
  {
    label: "更多",
    items: [
      { href: "/about", label: "关于", icon: "heart" },
      { href: "/changelog", label: "更新日志", icon: "history" },
      { href: "/feedback", label: "反馈", icon: "message" },
    ],
  },
  {
    label: "管理",
    items: [
      { href: "/admin/sources", label: "信源管理", icon: "source", access: "operator" },
      { href: "/admin/crawls", label: "采集监控", icon: "activity", access: "operator" },
      { href: "/admin/content", label: "内容审核", icon: "review", access: "operator" },
      { href: "/admin/reports", label: "报告编辑", icon: "report", access: "operator" },
      { href: "/admin/models", label: "模型与 Prompt", icon: "settings", access: "admin" },
      { href: "/admin/readiness", label: "发布就绪检查", icon: "activity", access: "admin" },
      { href: "/admin/operations", label: "投递与 Agent 审批", icon: "mail", access: "admin" },
      { href: "/admin/users", label: "用户与邀请", icon: "users", access: "admin" },
    ],
  },
];
