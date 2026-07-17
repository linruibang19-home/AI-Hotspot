import { redirect } from "next/navigation";

export default function MonthlyReportAlias() {
  redirect("/reports?period=MONTHLY");
}
