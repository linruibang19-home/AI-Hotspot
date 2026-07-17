import type { ReactNode } from "react";
import { Sidebar } from "@/components/sidebar";
import { AuthProvider } from "@/components/auth-provider";

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <AuthProvider>
      <div className="app-shell">
        <Sidebar />
        <main className="main-shell" id="main-content">{children}</main>
      </div>
    </AuthProvider>
  );
}
