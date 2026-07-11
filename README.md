# hubitat-driver

Custom Hubitat Elevation drivers, organized one folder per project. Each
project folder is self-contained with its own README, driver code, and
(where relevant) companion scripts.

## Drivers

| Project | Description |
|---|---|
| [`wan-failover-monitor/`](wan-failover-monitor/) | Detects a UniFi 5G/LTE WAN failover via public IP polling, auto-updates Cloudflare DDNS, and restarts affected Docker containers (via Portainer) / Proxmox LXCs (via Proxmox VE API) |
| [`awair-omni-local/`](awair-omni-local/) | Polls an Awair Omni air quality monitor's local API for temperature, humidity, CO2, VOC, PM2.5, lux, noise, and a locally-calculated EPA AQI. Canonical repo/HPM package remains [`Hubitat-AwAir`](https://github.com/kwon2288/Hubitat-AwAir); this copy is kept here for browsing convenience only. |

More drivers will be added here over time — see each project's own
`README.md` for installation and configuration details specific to that
driver.

## Repository structure

```
hubitat-driver/
├── README.md                  (this file)
├── LICENSE                    (Apache 2.0, applies repo-wide)
└── <project-name>/
    ├── README.md               project-specific docs
    ├── drivers/                 .groovy driver files for that project
    │   └── *.groovy
    └── scripts/                 optional companion scripts (non-Hubitat)
        └── *.sh
```

Adding a new driver means adding a new top-level `<project-name>/`
folder following this same layout.

## Author

[kwon2288](https://github.com/kwon2288) — see also [Hubitat-AwAir](https://github.com/kwon2288/Hubitat-AwAir) for other published drivers.

## License

Apache License 2.0 applies repo-wide by default — see [LICENSE](LICENSE).
Individual project folders may specify a different license in their own
`LICENSE` file (e.g. `awair-omni-local/` uses CC0); that project-level
license takes precedence for that folder's contents.
