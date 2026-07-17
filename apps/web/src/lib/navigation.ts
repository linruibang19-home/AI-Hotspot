export type NavigationItem = {
  href: string;
  label: string;
  icon: string;
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
      { href: "/", label: "精选", icon: "★" },
      { href: "/all", label: "全部 AI 动态", icon: "≡" },
      { href: "/reports", label: "AI 日报", icon: "▤" },
      { href: "/topics", label: "主题", icon: "⌘" },
      { href: "/favorites", label: "收藏", icon: "♡" },
    ],
  },
  {
    label: "智能",
    items: [
      { href: "/subscriptions", label: "订阅与邮件", icon: "✉", access: "authenticated" },
      { href: "/research", label: "知识库问答", icon: "◫", access: "authenticated" },
      { href: "/agent", label: "Agent 工作台", icon: "◎", access: "authenticated" },
    ],
  },
  {
    label: "管理",
    items: [
      { href: "/admin/sources", label: "信源管理", icon: "⌁", access: "operator" },
      { href: "/admin/crawls", label: "采集监控", icon: "◌", access: "operator" },
      { href: "/admin/content", label: "内容审核", icon: "✓", access: "operator" },
      { href: "/admin/models", label: "模型与 Prompt", icon: "⚙", access: "admin" },
      { href: "/admin/users", label: "用户与邀请", icon: "◇", access: "admin" },
    ],
  },
];
