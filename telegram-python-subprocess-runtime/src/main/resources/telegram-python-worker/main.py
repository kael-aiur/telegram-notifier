#!/usr/bin/env python3
import signal

from telegram_worker.worker import main

if __name__ == "__main__":
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    main()
