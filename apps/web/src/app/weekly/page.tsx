import { redirect } from "next/navigation";

export default function WeeklyReportAlias() {
  redirect("/reports?period=WEEKLY");
}
