/**
 * SSH always use command data
 * by category group weave, support search and fast speed insert
 */

export interface CommandItem {
  /** command text */
  command: string
  /** show description(optional) */
  description?: string
}

export interface CommandCategory {
  /** category ID */
  id: string
  /** category name */
  name: string
  /** category icon emoji */
  icon: string
  /** category down command list */
  commands: CommandItem[]
}

export const commandCategories: CommandCategory[] = [
  {
    id: 'file-ops',
    name: 'Files and directories',
    icon: '📁',
    commands: [
      { command: 'ls -la', description: 'List directory details' },
      { command: 'pwd', description: 'Show current directory' },
      { command: 'cd /home', description: 'Change to the home directory' },
      { command: 'mkdir test', description: 'Create directory' },
      { command: 'rmdir test', description: 'Remove empty directory' },
      { command: 'cp source.txt dest.txt', description: 'Copy file' },
      { command: 'mv old.txt new.txt', description: 'Move/rename file' },
      { command: 'rm -rf test', description: 'Force-delete a directory' },
      { command: 'find / -name "*.log"', description: 'Find files' },
      { command: 'locate filename', description: 'Quick file lookup' },
    ],
  },
  {
    id: 'file-content',
    name: 'File contents',
    icon: '📄',
    commands: [
      { command: 'cat file.txt', description: 'View file contents' },
      { command: 'less file.txt', description: 'Page through a file' },
      { command: 'head -n 20 file.txt', description: 'Show file header' },
      { command: 'tail -f file.log', description: 'Follow the log' },
      { command: 'grep "keyword" file.txt', description: 'Search for a pattern' },
      { command: 'wc -l file.txt', description: 'Count lines' },
      { command: 'sort file.txt', description: 'Sort contents' },
      { command: 'uniq file.txt', description: 'Dedupe adjacent lines' },
    ],
  },
  {
    id: 'system-info',
    name: 'System info',
    icon: '⚙️',
    commands: [
      { command: 'ps aux', description: 'List all processes' },
      { command: 'top', description: 'Live process monitor' },
      { command: 'htop', description: 'Interactive process viewer' },
      { command: 'df -h', description: 'Disk usage' },
      { command: 'free -m', description: 'Memory usage' },
      { command: 'uptime', description: 'Uptime' },
      { command: 'uname -a', description: 'System info' },
      { command: 'lscpu', description: 'CPU info' },
      { command: 'lsblk', description: 'Block devices' },
    ],
  },
  {
    id: 'user-permission',
    name: 'Users and permissions',
    icon: '👤',
    commands: [
      { command: 'whoami', description: 'Show current user' },
      { command: 'id', description: 'Show user ID and groups' },
      { command: 'who', description: 'Show logged-in users' },
      { command: 'w', description: 'Show user details' },
      { command: 'chmod 755 file', description: 'Set permissions' },
      { command: 'chmod +x script.sh', description: 'Add execute permission' },
      { command: 'chmod 644 file', description: 'Set file permissions' },
      { command: 'chmod -R 755 dir', description: 'Recursively chmod a directory' },
      { command: 'chown user:group file', description: 'Change owner' },
      { command: 'su - username', description: 'Switch user' },
      { command: 'sudo command', description: 'Run as administrator' },
    ],
  },
  {
    id: 'network',
    name: 'Network and services',
    icon: '🌐',
    commands: [
      { command: 'ping -c 4 host', description: 'Test network connectivity' },
      { command: 'wget url', description: 'Download file' },
      { command: 'curl -s url', description: 'Request a URL' },
      { command: 'netstat -tuln', description: 'Show listening ports' },
      { command: 'ss -tuln', description: 'Show network connections' },
      { command: 'systemctl status service', description: 'Show service status' },
      { command: 'systemctl start service', description: 'Start service' },
      { command: 'systemctl stop service', description: 'Stop service' },
      { command: 'systemctl restart service', description: 'Restart service' },
      { command: 'iptables -L', description: 'Show firewall rules' },
    ],
  },
  {
    id: 'system-manage',
    name: 'System admin',
    icon: '🔧',
    commands: [
      { command: 'yum update', description: 'Update packages (CentOS)' },
      { command: 'yum install package', description: 'Install a package' },
      { command: 'yum remove package', description: 'Uninstall a package' },
      { command: 'apt update && apt upgrade', description: 'Update packages (Debian)' },
      { command: 'apt install package', description: 'Install a package' },
      { command: 'rpm -qa | grep package', description: 'List installed packages' },
      { command: 'crontab -l', description: 'Show crontab' },
      { command: 'crontab -e', description: 'Edit crontab' },
      { command: 'mount /dev/sdb1 /mnt', description: 'Mount disk' },
      { command: 'umount /mnt', description: 'Unmount disk' },
      { command: 'history', description: 'Show command history' },
      { command: 'clear', description: 'Clear' },
    ],
  },
  {
    id: 'git',
    name: 'Git',
    icon: '📦',
    commands: [
      { command: 'git clone url', description: 'Clone repository' },
      { command: 'git status', description: 'Show status' },
      { command: 'git add .', description: 'Stage all changes' },
      { command: 'git commit -m "message"', description: 'Commit changes' },
      { command: 'git push', description: 'Push to remote' },
      { command: 'git pull', description: 'Pull updates' },
      { command: 'git checkout branch', description: 'Switch branch' },
      { command: 'git branch', description: 'List branches' },
      { command: 'git log --oneline -10', description: 'Show commit history' },
      { command: 'git reset --hard HEAD~1', description: 'Undo the last commit' },
      { command: 'git stash', description: 'Stash changes' },
      { command: 'git stash pop', description: 'Pop stash' },
    ],
  },
  {
    id: 'docker',
    name: 'Docker',
    icon: '🐳',
    commands: [
      { command: 'docker ps', description: 'List running containers' },
      { command: 'docker ps -a', description: 'List all containers' },
      { command: 'docker images', description: 'List images' },
      { command: 'docker run -it image', description: 'Run container' },
      { command: 'docker stop container', description: 'Stop container' },
      { command: 'docker start container', description: 'Start container' },
      { command: 'docker restart container', description: 'Restart container' },
      { command: 'docker exec -it container bash', description: 'Enter container' },
      { command: 'docker build -t name .', description: 'Build image' },
      { command: 'docker rm container', description: 'Remove container' },
      { command: 'docker rmi image', description: 'Remove image' },
      { command: 'docker logs -f container', description: 'Show container logs' },
    ],
  },
  {
    id: 'maven',
    name: 'Maven',
    icon: '☕',
    commands: [
      { command: 'mvn clean install', description: 'Clean and build' },
      { command: 'mvn clean compile', description: 'Clean and compile' },
      { command: 'mvn test', description: 'Run tests' },
      { command: 'mvn package', description: 'Package' },
      { command: 'mvn clean', description: 'Clean' },
      { command: 'mvn dependency:tree', description: 'Show dependency tree' },
      { command: 'mvn spring-boot:run', description: 'Run Spring Boot' },
      { command: 'mvn archetype:generate', description: 'Generate a project skeleton' },
    ],
  },
  {
    id: 'other',
    name: 'Other',
    icon: '🔍',
    commands: [
      { command: 'date', description: 'Show date and time' },
      { command: 'cal', description: 'Show calendar' },
      { command: 'which command', description: 'Locate a command' },
      { command: 'whereis command', description: 'Locate a command' },
      { command: 'man command', description: 'Open the man page' },
      { command: 'command --help', description: 'Show help' },
      { command: 'exit', description: 'Exit the current session' },
      { command: 'logout', description: 'Log out' },
    ],
  },
]
