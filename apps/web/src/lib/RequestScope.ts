/** Discards late responses after selection/draft changes without retrying their side effects. */
export class RequestScope {
  private generation = 0
  advance() { this.generation++ }
  capture() { const generation = this.generation; return () => generation === this.generation }
}
