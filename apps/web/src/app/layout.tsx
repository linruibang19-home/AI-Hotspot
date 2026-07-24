import type { Metadata } from "next";
import { AppShell } from "@/components/app-shell";
import "./globals.css";
import "./styles/report.css";
import "./styles/public-product.css";

export const metadata: Metadata = {
  title: {
    default: "AI Hotspot",
    template: "%s · AI Hotspot",
  },
  description: "面向 AI 从业者的公开情报、研究、订阅与自动化工作台。",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
