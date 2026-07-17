import { redirect } from "next/navigation";

export default function DailyReportAlias() {
  redirect("/reports?period=DAILY");
}
