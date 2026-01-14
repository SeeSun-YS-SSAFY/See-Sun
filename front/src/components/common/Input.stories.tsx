import type { Meta, StoryObj } from "@storybook/nextjs-vite";
import Input from "./Input";

const meta = {
    title: 'Common/Input',
    component: Input,
    parameters: {
        layout: 'centered'
    },
    argTypes: {
        width: {
            control: 'text',
            description: 'Container width',
        }
    },
    decorators: [
        (Story, context) => (
            <div style={{ width: context.args.width || '100%' }}>
                <Story />
            </div>
        )
    ]
} satisfies Meta<typeof Input>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
    args: {
        placeholder: '입력 텍스트',
        width: '400px',
    }
}