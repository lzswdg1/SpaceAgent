import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const FIXTURES = resolve(process.cwd(), "../../contracts/multi-agent/v1/fixtures");

export async function fixture(name: string): Promise<unknown> {
  return JSON.parse(await readFile(resolve(FIXTURES, name), "utf8"));
}
