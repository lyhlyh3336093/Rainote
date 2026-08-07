# -*- coding: utf-8 -*-
"""
从 surefire-reports/TEST-*.xml 生成自包含 HTML 测试报告。
无外部 CSS/JS 依赖，可直接用浏览器打开。

用法:
    python generate-test-report.py [surefire-reports-dir] [output-html]

默认:
    surefire-reports-dir = target/surefire-reports
    output-html          = target/reports/surefire-standalone.html
"""
import os
import sys
import glob
import xml.etree.ElementTree as ET
from datetime import datetime


def parse_xml(filepath):
    """解析单个 TEST-*.xml，返回 (class_name, tests, failures, errors, skipped, time, testcases)"""
    tree = ET.parse(filepath)
    root = tree.getroot()  # <testsuite>

    class_name = root.get("name", "unknown")
    tests = int(root.get("tests", "0"))
    failures = int(root.get("failures", "0"))
    errors = int(root.get("errors", "0"))
    skipped = int(root.get("skipped", "0"))
    time = float(root.get("time", "0"))

    testcases = []
    for tc in root.findall("testcase"):
        case_name = tc.get("name", "unknown")
        case_time = float(tc.get("time", "0"))
        classname = tc.get("classname", class_name)

        # 判断状态
        failure_elem = tc.find("failure")
        error_elem = tc.find("error")
        skipped_elem = tc.find("skipped")
        if error_elem is not None:
            status = "error"
            detail = (error_elem.text or "").strip()
        elif failure_elem is not None:
            status = "failure"
            detail = (failure_elem.text or "").strip()
        elif skipped_elem is not None:
            status = "skipped"
            detail = (skipped_elem.text or "").strip()
        else:
            status = "pass"
            detail = ""

        testcases.append({
            "name": case_name,
            "classname": classname,
            "time": case_time,
            "status": status,
            "detail": detail,
        })

    return {
        "class_name": class_name,
        "tests": tests,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "time": time,
        "testcases": testcases,
    }


def generate_html(suites, output_path):
    """生成自包含 HTML 报告"""
    total_tests = sum(s["tests"] for s in suites)
    total_failures = sum(s["failures"] for s in suites)
    total_errors = sum(s["errors"] for s in suites)
    total_skipped = sum(s["skipped"] for s in suites)
    total_time = sum(s["time"] for s in suites)
    all_pass = (total_failures == 0 and total_errors == 0)

    # 收集所有 testcase
    all_cases = []
    for s in suites:
        for tc in s["testcases"]:
            all_cases.append(tc)
    failed_cases = [c for c in all_cases if c["status"] in ("failure", "error")]

    overall_color = "#28a745" if all_pass else "#dc3545"
    overall_text = "全部通过" if all_pass else "存在失败"

    html_parts = []
    html_parts.append("""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>单元测试报告 - ruoyi-system</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "PingFang SC", "Microsoft YaHei", sans-serif; background: #f5f5f5; color: #333; line-height: 1.6; }
  .container { max-width: 1200px; margin: 0 auto; padding: 20px; }
  .header { background: #fff; padding: 24px 30px; border-radius: 8px; margin-bottom: 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
  .header h1 { font-size: 24px; margin-bottom: 8px; }
  .header .meta { color: #888; font-size: 14px; }
  .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 16px; margin-bottom: 20px; }
  .stat-card { background: #fff; padding: 20px; border-radius: 8px; text-align: center; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
  .stat-card .number { font-size: 32px; font-weight: 700; }
  .stat-card .label { font-size: 13px; color: #888; margin-top: 4px; text-transform: uppercase; letter-spacing: 0.5px; }
  .stat-pass .number { color: #28a745; }
  .stat-fail .number { color: #dc3545; }
  .stat-error .number { color: #dc3545; }
  .stat-skip .number { color: #ffc107; }
  .stat-total .number { color: #0366d6; }
  .stat-time .number { color: #6f42c1; font-size: 24px; }
  .overall-badge { display: inline-block; padding: 6px 16px; border-radius: 20px; font-size: 14px; font-weight: 600; color: #fff; }
  .section { background: #fff; border-radius: 8px; margin-bottom: 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); overflow: hidden; }
  .section-header { padding: 16px 24px; border-bottom: 1px solid #eee; font-size: 18px; font-weight: 600; cursor: pointer; user-select: none; }
  .section-header:hover { background: #f9f9f9; }
  table { width: 100%; border-collapse: collapse; }
  th, td { padding: 10px 16px; text-align: left; border-bottom: 1px solid #f0f0f0; font-size: 14px; }
  th { background: #fafafa; font-weight: 600; color: #555; }
  tr:hover { background: #f9f9ff; }
  .status-pass { color: #28a745; font-weight: 600; }
  .status-failure { color: #dc3545; font-weight: 600; }
  .status-error { color: #dc3545; font-weight: 600; }
  .status-skipped { color: #ffc107; font-weight: 600; }
  .bar { display: inline-block; width: 60px; height: 8px; border-radius: 4px; background: #28a745; vertical-align: middle; }
  .bar-fail { background: #dc3545; }
  .detail-box { background: #fff5f5; border-left: 3px solid #dc3545; padding: 12px 16px; margin: 8px 0; font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace; font-size: 13px; white-space: pre-wrap; word-break: break-all; max-height: 300px; overflow-y: auto; }
  .toggle-icon { float: right; transition: transform 0.2s; }
  .collapsed .toggle-icon { transform: rotate(-90deg); }
  .collapsed + .section-body { display: none; }
  .footer { text-align: center; padding: 20px; color: #aaa; font-size: 12px; }
</style>
</head>
<body>
<div class="container">
""")

    # Header
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    html_parts.append(f"""  <div class="header">
    <h1>单元测试报告 <span class="overall-badge" style="background:{overall_color}">{overall_text}</span></h1>
    <div class="meta">模块: ruoyi-system &nbsp;|&nbsp; 生成时间: {now} &nbsp;|&nbsp; 测试套件数: {len(suites)}</div>
  </div>
""")

    # Summary cards
    success_rate = ((total_tests - total_failures - total_errors - total_skipped) / total_tests * 100) if total_tests > 0 else 0
    html_parts.append(f"""  <div class="summary">
    <div class="stat-card stat-total"><div class="number">{total_tests}</div><div class="label">总测试数</div></div>
    <div class="stat-card stat-pass"><div class="number">{total_tests - total_failures - total_errors - total_skipped}</div><div class="label">通过</div></div>
    <div class="stat-card stat-fail"><div class="number">{total_failures}</div><div class="label">失败</div></div>
    <div class="stat-card stat-error"><div class="number">{total_errors}</div><div class="label">错误</div></div>
    <div class="stat-card stat-skip"><div class="number">{total_skipped}</div><div class="label">跳过</div></div>
    <div class="stat-card stat-time"><div class="number">{total_time:.3f}s</div><div class="label">总耗时</div></div>
    <div class="stat-card"><div class="number" style="color:{overall_color}">{success_rate:.1f}%</div><div class="label">成功率</div></div>
  </div>
""")

    # Failed cases section (if any)
    if failed_cases:
        html_parts.append("""  <div class="section">
    <div class="section-header" onclick="this.classList.toggle('collapsed')">❌ 失败用例详情 <span class="toggle-icon">▼</span></div>
    <div class="section-body">
      <table>
        <thead><tr><th>测试类</th><th>测试方法</th><th>状态</th><th>耗时</th></tr></thead>
        <tbody>
""")
        for c in failed_cases:
            short_class = c["classname"].replace("com.ruoyi.system.", "")
            html_parts.append(f'          <tr><td>{short_class}</td><td>{c["name"]}</td><td class="status-{c["status"]}">{c["status"]}</td><td>{c["time"]:.3f}s</td></tr>\n')
            if c["detail"]:
                escaped = c["detail"].replace("<", "&lt;").replace(">", "&gt;").replace("&", "&amp;")
                html_parts.append(f'          <tr><td colspan="4"><div class="detail-box">{escaped}</div></td></tr>\n')
        html_parts.append("""        </tbody>
      </table>
    </div>
  </div>
""")

    # Per-suite breakdown
    html_parts.append("""  <div class="section">
    <div class="section-header" onclick="this.classList.toggle('collapsed')">📊 测试套件明细 <span class="toggle-icon">▼</span></div>
    <div class="section-body">
      <table>
        <thead><tr><th>测试类</th><th>总数</th><th>通过</th><th>失败</th><th>错误</th><th>跳过</th><th>耗时</th><th>成功率</th></tr></thead>
        <tbody>
""")
    for s in sorted(suites, key=lambda x: x["class_name"]):
        short_name = s["class_name"].replace("com.ruoyi.system.", "")
        passed = s["tests"] - s["failures"] - s["errors"] - s["skipped"]
        rate = (passed / s["tests"] * 100) if s["tests"] > 0 else 0
        bar_class = "" if rate == 100 else " bar-fail"
        html_parts.append(
            f'          <tr><td>{short_name}</td><td>{s["tests"]}</td><td>{passed}</td>'
            f'<td>{s["failures"]}</td><td>{s["errors"]}</td><td>{s["skipped"]}</td>'
            f'<td>{s["time"]:.3f}s</td><td><span class="bar{bar_class}"></span> {rate:.0f}%</td></tr>\n'
        )
    html_parts.append(f"""        </tbody>
        <tfoot><tr style="font-weight:700;background:#fafafa;">
          <td>合计</td><td>{total_tests}</td><td>{total_tests - total_failures - total_errors - total_skipped}</td>
          <td>{total_failures}</td><td>{total_errors}</td><td>{total_skipped}</td>
          <td>{total_time:.3f}s</td><td>{success_rate:.1f}%</td>
        </tr></tfoot>
      </table>
    </div>
  </div>
""")

    # All test cases
    html_parts.append("""  <div class="section">
    <div class="section-header" onclick="this.classList.toggle('collapsed')">📝 全部用例 <span class="toggle-icon">▼</span></div>
    <div class="section-body">
      <table>
        <thead><tr><th>测试类</th><th>测试方法</th><th>状态</th><th>耗时</th></tr></thead>
        <tbody>
""")
    for c in sorted(all_cases, key=lambda x: (x["classname"], x["name"])):
        short_class = c["classname"].replace("com.ruoyi.system.", "")
        html_parts.append(
            f'          <tr><td>{short_class}</td><td>{c["name"]}</td>'
            f'<td class="status-{c["status"]}">{c["status"]}</td><td>{c["time"]:.3f}s</td></tr>\n'
        )
    html_parts.append("""        </tbody>
      </table>
    </div>
  </div>
""")

    html_parts.append(f"""  <div class="footer">由 generate-test-report.py 从 surefire-reports XML 生成 | {now}</div>
</div>
</body>
</html>
""")

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write("".join(html_parts))
    return total_tests, total_failures, total_errors, total_skipped


def main():
    reports_dir = sys.argv[1] if len(sys.argv) > 1 else "target/surefire-reports"
    output_html = sys.argv[2] if len(sys.argv) > 2 else "target/reports/surefire-standalone.html"

    xml_files = sorted(glob.glob(os.path.join(reports_dir, "TEST-*.xml")))
    if not xml_files:
        print("ERROR: 未找到 TEST-*.xml 文件于 {}".format(reports_dir))
        sys.exit(1)

    print("找到 {} 个测试结果文件".format(len(xml_files)))
    suites = [parse_xml(f) for f in xml_files]
    total, fail, err, skip = generate_html(suites, output_html)

    print("报告已生成: {}".format(output_html))
    print("汇总: {} tests, {} failures, {} errors, {} skipped".format(total, fail, err, skip))


if __name__ == "__main__":
    main()
